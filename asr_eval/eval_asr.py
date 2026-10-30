#!/usr/bin/env python3
"""ASR evaluation harness (PC side).

Computes Chinese CER (character error rate) against a reference transcript set, so model
changes are judged by numbers instead of impressions.

Usage:
  python asr_eval/eval_asr.py --kind streaming --dir <model dir>
  python asr_eval/eval_asr.py --kind paraformer --dir <model dir>
  python asr_eval/eval_asr.py --kind sensevoice --dir <model dir>
  python asr_eval/eval_asr.py --kind ctc --dir <model dir>
"""
from __future__ import annotations

import argparse
import glob
import json
import os
import re
import sys
import time
import wave

import numpy as np

ROOT = os.path.dirname(os.path.abspath(__file__))
REFS = os.path.join(ROOT, "refs.json")
WAV_DIR = os.path.join(ROOT, "wav")

PUNCT = "，。！？、；：,.!?;:\"'“”‘’（）()《》 \t\n"


def norm(s: str) -> str:
    return "".join(ch for ch in s if ch not in PUNCT).lower()


def cer(ref: str, hyp: str) -> tuple[float, int, int]:
    r, h = norm(ref), norm(hyp)
    if not r:
        return 0.0, 0, 0
    d = np.zeros((len(r) + 1, len(h) + 1), dtype=np.int32)
    d[:, 0] = np.arange(len(r) + 1)
    d[0, :] = np.arange(len(h) + 1)
    for i in range(1, len(r) + 1):
        for j in range(1, len(h) + 1):
            cost = 0 if r[i - 1] == h[j - 1] else 1
            d[i, j] = min(d[i - 1, j] + 1, d[i, j - 1] + 1, d[i - 1, j - 1] + cost)
    return float(d[len(r), len(h)]) / len(r), int(d[len(r), len(h)]), len(r)


def read_wav(path: str, want_sr: int = 16000) -> np.ndarray:
    with wave.open(path) as w:
        sr = w.getframerate()
        ch = w.getnchannels()
        sw = w.getsampwidth()
        data = w.readframes(w.getnframes())
    if sw != 2:
        raise ValueError(f"{path}: only 16-bit supported (got {sw*8})")
    x = np.frombuffer(data, dtype=np.int16).astype(np.float32) / 32768.0
    if ch > 1:
        x = x.reshape(-1, ch).mean(axis=1)
    if sr != want_sr:
        # Linear interpolation is fine here: our sources are 16k already, this only guards
        # against an accidental 44.1k asset in the test set.
        n = int(round(len(x) * want_sr / sr))
        x = np.interp(np.linspace(0, len(x) - 1, n), np.arange(len(x)), x).astype(np.float32)
    return x


def build(kind: str, d: str):
    import sherpa_onnx

    find = lambda pat: sorted(glob.glob(os.path.join(d, pat)))
    tokens = os.path.join(d, "tokens.txt")

    if kind == "streaming":
        enc = [f for f in find("*encoder*.onnx") if "int8" in f] or find("*encoder*.onnx")
        dec = [f for f in find("*decoder*.onnx") if "int8" in f] or find("*decoder*.onnx")
        joi = [f for f in find("*joiner*.onnx") if "int8" in f] or find("*joiner*.onnx")
        return sherpa_onnx.OnlineRecognizer.from_transducer(
            encoder=enc[0], decoder=dec[0], joiner=joi[0], tokens=tokens,
            num_threads=2, sample_rate=16000, feature_dim=80,
            enable_endpoint_detection=True, rule1_min_trailing_silence=2.4,
            rule2_min_trailing_silence=1.2, rule3_min_utterance_length=20.0,
            decoding_method="greedy_search",
        )
    if kind == "paraformer":
        m = find("*paraformer*.onnx") or find("*model*.onnx")
        return sherpa_onnx.OfflineRecognizer.from_paraformer(
            paraformer=m[0], tokens=tokens, num_threads=2, sample_rate=16000,
            feature_dim=80, decoding_method="greedy_search",
        )
    if kind == "sensevoice":
        m = find("*model*.onnx")
        return sherpa_onnx.OfflineRecognizer.from_sense_voice(
            model=m[0], tokens=tokens, num_threads=2, use_itn=True,
        )
    if kind == "zipformer_ctc":
        m = [f for f in find("*model*.onnx") if "int8" in f] or find("*model*.onnx")
        return sherpa_onnx.OfflineRecognizer.from_zipformer_ctc(
            model=m[0], tokens=tokens, num_threads=2,
        )
    if kind == "ctc":
        m = [f for f in find("*model*.onnx") if "int8" in f] or find("*model*.onnx")
        return sherpa_onnx.OfflineRecognizer.from_nemo_ctc(model=m[0], tokens=tokens, num_threads=2)
    if kind == "transducer-offline":
        enc = [f for f in find("*encoder*.onnx") if "int8" in f] or find("*encoder*.onnx")
        dec = [f for f in find("*decoder*.onnx") if "int8" in f] or find("*decoder*.onnx")
        joi = [f for f in find("*joiner*.onnx") if "int8" in f] or find("*joiner*.onnx")
        return sherpa_onnx.OfflineRecognizer.from_transducer(
            encoder=enc[0], decoder=dec[0], joiner=joi[0], tokens=tokens, num_threads=2,
        )
    raise SystemExit(f"unknown kind {kind}")


def transcribe(rec, kind: str, x: np.ndarray) -> str:
    import sherpa_onnx
    if kind == "streaming":
        s = rec.create_stream()
        s.accept_waveform(16000, x)
        # tail padding so the last frames get flushed
        s.accept_waveform(16000, np.zeros(int(16000 * 0.5), dtype=np.float32))
        s.input_finished()
        while rec.is_ready(s):
            rec.decode_stream(s)
        r = rec.get_result(s)
        return r if isinstance(r, str) else r.text
    s = rec.create_stream()
    s.accept_waveform(16000, x)
    rec.decode_stream(s)
    return s.result.text


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--kind", required=True)
    ap.add_argument("--dir", required=True)
    ap.add_argument("--label", default="")
    ap.add_argument("--json-out", default="")
    args = ap.parse_args()

    refs = json.load(open(REFS, encoding="utf-8-sig"))
    label = args.label or f"{args.kind}:{os.path.basename(args.dir.rstrip(os.sep))}"
    t0 = time.time()
    rec = build(args.kind, args.dir)
    load_s = time.time() - t0

    rows = []
    total_err = total_len = 0
    lat = []
    for r in refs:
        x = read_wav(r["wav"])
        t1 = time.time()
        hyp = transcribe(rec, args.kind, x)
        lat.append(time.time() - t1)
        e, n = cer(r["text"], hyp)[1:]
        total_err += e
        total_len += n
        rows.append({"id": r["id"], "ref": r["text"], "hyp": hyp, "err": e, "len": n,
                     "ok": norm(r["text"]) == norm(hyp)})
        print(f'{r["id"]}  CER {e:>2}/{n:<2}  ref={r["text"]}')
        print(f'          hyp={hyp}')

    overall = total_err / total_len if total_len else 0
    exact = sum(1 for x in rows if x["ok"]) / len(rows)
    print("\n===== SUMMARY =====")
    print(f"model      : {label}")
    print(f"load time  : {load_s:.2f}s")
    print(f"utts       : {len(rows)}")
    print(f"overall CER: {overall*100:.1f}%")
    print(f"exact match: {exact*100:.1f}%")
    print(f"avg latency: {sum(lat)/len(lat)*1000:.0f} ms (per utterance, incl. decode)")
    if args.json_out:
        json.dump({"model": label, "cer": overall, "exact": exact, "rows": rows,
                   "load_s": load_s, "latency_ms": lat},
                  open(args.json_out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
