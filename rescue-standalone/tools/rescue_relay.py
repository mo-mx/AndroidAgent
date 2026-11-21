#!/usr/bin/env python3
"""ClosePaw rescue relay — PC side.

The phone only makes OUTBOUND requests (poll/result) so this works behind NAT, over Wi-Fi and
over mobile data (through a tunnel). Auth = HMAC-SHA256 over "<ts>\n<path>\n<body>" with a
shared secret baked into the APK; timestamps outside a 120 s window are rejected.

Files (created next to this script, in ./relay_data/):
  relay.json     {secret, port, cert, key}
  queue.json     pending commands (list)
  results.jsonl  append-only results
  devices.jsonl  last-seen per device

Usage:
  python rescue_relay.py init --secret <S> [--port 8765]
  python rescue_relay.py serve
  python rescue_relay.py send status
  python rescue_relay.py send speak --text "已连接"
  python rescue_relay.py send start_agent
  python rescue_relay.py results [--n 20]
  python rescue_relay.py devices
"""
from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import os
import ssl
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DATA = os.path.join(os.path.dirname(os.path.abspath(__file__)), "relay_data")
CONF = os.path.join(DATA, "relay.json")
QUEUE = os.path.join(DATA, "queue.json")
RESULTS = os.path.join(DATA, "results.jsonl")
DEVICES = os.path.join(DATA, "devices.jsonl")
LOCK = threading.Lock()
SKEW = 120


def _ensure():
    os.makedirs(DATA, exist_ok=True)


def _load_conf() -> dict:
    _ensure()
    if not os.path.exists(CONF):
        sys.exit(f"missing {CONF}; run: python rescue_relay.py init --secret <S>")
    with open(CONF, encoding="utf-8") as f:
        return json.load(f)


def _sign(secret: str, ts: str, path: str, body: str) -> str:
    msg = f"{ts}\n{path}\n{body}".encode()
    return hmac.new(secret.encode(), msg, hashlib.sha256).hexdigest()


def _read_queue() -> list:
    if not os.path.exists(QUEUE):
        return []
    with open(QUEUE, encoding="utf-8") as f:
        try:
            return json.load(f)
        except Exception:
            return []


def _write_queue(items: list):
    tmp = QUEUE + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(items, f, ensure_ascii=False, indent=1)
    os.replace(tmp, QUEUE)


def _append(path: str, obj: dict):
    with open(path, "a", encoding="utf-8") as f:
        f.write(json.dumps(obj, ensure_ascii=False) + "\n")


class Handler(BaseHTTPRequestHandler):
    server_version = "ClosePawRescue/0.1"
    protocol_version = "HTTP/1.1"
    secret = ""

    def log_message(self, fmt, *args):
        sys.stderr.write("[relay] " + (fmt % args) + "\n")

    def _json(self, code: int, obj: dict):
        data = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _auth(self, body: str) -> bool:
        ts = self.headers.get("X-Rescue-Ts", "")
        sig = self.headers.get("X-Rescue-Sig", "")
        if not ts or not sig:
            return False
        try:
            if abs(time.time() - int(ts)) > SKEW:
                return False
        except ValueError:
            return False
        expect = _sign(self.secret, ts, self.path.split("?")[0], body)
        return hmac.compare_digest(expect, sig)

    def do_GET(self):
        if self.path == "/health":
            self._json(200, {"ok": True, "ts": int(time.time())})
        else:
            self._json(404, {"error": "not found"})

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length).decode("utf-8", "ignore") if length else ""
        if not self._auth(body):
            self._json(401, {"error": "bad signature"})
            return
        path = self.path.split("?")[0]
        try:
            payload = json.loads(body) if body else {}
        except Exception:
            self._json(400, {"error": "bad json"})
            return
        device = payload.get("device") or self.headers.get("X-Rescue-Device") or "?"

        if path == "/poll":
            with LOCK:
                q = _read_queue()
                mine = [c for c in q if c.get("device") in (None, "", device)]
                cmd = None
                if mine:
                    cmd = mine[0]
                    q = [c for c in q if c.get("id") != cmd["id"]]
                    _write_queue(q)
                _append(DEVICES, {"ts": int(time.time()), "device": device,
                                  "screen_on": payload.get("screen_on")})
            if cmd:
                self._json(200, {"id": cmd["id"], "op": cmd["op"], **{k: v for k, v in cmd.items() if k not in ("id", "op", "device")}})
            else:
                self._json(200, {"id": ""})
            return

        if path == "/result":
            with LOCK:
                _append(RESULTS, payload)
            self._json(200, {"ok": True})
            return

        self._json(404, {"error": "not found"})


def cmd_init(args):
    _ensure()
    cert = os.path.join(DATA, "cert.pem")
    key = os.path.join(DATA, "key.pem")
    if not (os.path.exists(cert) and os.path.exists(key)):
        _make_cert(cert, key, args.ip)
    conf = {"secret": args.secret, "port": args.port, "cert": cert, "key": key}
    with open(CONF, "w", encoding="utf-8") as f:
        json.dump(conf, f, indent=1)
    with open(cert, "rb") as f:
        pem = f.read()
    import base64
    body = "".join(
        line for line in pem.decode().splitlines() if "-----" not in line
    )
    der = base64.b64decode(body)
    pin = hashlib.sha256(der).hexdigest()
    print("relay.json written:", CONF)
    print("cert:", cert)
    print("RESCUE_PIN =", pin)
    print("endpoint  =", f"https://{args.ip}:{args.port}")


def _make_cert(cert: str, key: str, ip: str):
    """Self-signed cert via `cryptography` (falls back to the openssl CLI)."""
    try:
        from cryptography import x509
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import rsa
        from cryptography.x509.oid import NameOID
        import datetime
        import ipaddress

        k = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "closepaw-rescue")])
        now = datetime.datetime.now(datetime.timezone.utc)
        c = (
            x509.CertificateBuilder()
            .subject_name(name).issuer_name(name)
            .public_key(k.public_key())
            .serial_number(x509.random_serial_number())
            .not_valid_before(now - datetime.timedelta(days=1))
            .not_valid_after(now + datetime.timedelta(days=3650))
            .add_extension(x509.SubjectAlternativeName([
                x509.DNSName("localhost"),
                x509.IPAddress(ipaddress.ip_address(ip)),
            ]), critical=False)
            .sign(k, hashes.SHA256())
        )
        with open(key, "wb") as f:
            f.write(k.private_bytes(
                serialization.Encoding.PEM,
                serialization.PrivateFormat.TraditionalOpenSSL,
                serialization.NoEncryption(),
            ))
        with open(cert, "wb") as f:
            f.write(c.public_bytes(serialization.Encoding.PEM))
        print("self-signed cert generated (python cryptography)")
    except Exception as e:
        import subprocess
        print("cryptography unavailable/failed:", e, "→ falling back to openssl")
        subprocess.run(
            ["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
             "-keyout", key, "-out", cert, "-days", "3650",
             "-subj", "/CN=closepaw-rescue",
             "-addext", f"subjectAltName=IP:{ip},DNS:localhost"],
            check=True, capture_output=True,
        )
        print("self-signed cert generated (openssl)")


def cmd_serve(args):
    conf = _load_conf()
    Handler.secret = conf["secret"]
    port = args.port or conf["port"]
    srv = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    if args.plain:
        # Plain HTTP for use behind a TLS-terminating tunnel (e.g. cloudflared).
        print(f"[relay] listening on http://0.0.0.0:{port} (plain, behind tunnel)")
    else:
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain(conf["cert"], conf["key"])
        srv.socket = ctx.wrap_socket(srv.socket, server_side=True)
        print(f"[relay] listening on https://0.0.0.0:{port}  (cert {conf['cert']})")
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("\n[relay] bye")


def cmd_send(args):
    _ensure()
    cmd = {"id": uuid.uuid4().hex[:12], "op": args.op}
    if args.device:
        cmd["device"] = args.device
    if args.text:
        cmd["text"] = args.text
    with LOCK:
        q = _read_queue()
        q.append(cmd)
        _write_queue(q)
    print(json.dumps(cmd, ensure_ascii=False))


def cmd_results(args):
    if not os.path.exists(RESULTS):
        print("(no results yet)")
        return
    with open(RESULTS, encoding="utf-8") as f:
        lines = f.readlines()[-args.n:]
    for line in lines:
        try:
            obj = json.loads(line)
        except Exception:
            continue
        print(json.dumps(obj, ensure_ascii=False))


def cmd_devices(args):
    if not os.path.exists(DEVICES):
        print("(no devices yet)")
        return
    last = {}
    with open(DEVICES, encoding="utf-8") as f:
        for line in f:
            try:
                d = json.loads(line)
            except Exception:
                continue
            last[d["device"]] = d
    for k, v in last.items():
        print(f"{k}\tlast_seen={time.strftime('%H:%M:%S', time.localtime(v['ts']))}\tscreen_on={v.get('screen_on')}")


def main():
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest="cmd", required=True)

    i = sub.add_parser("init")
    i.add_argument("--secret", required=True)
    i.add_argument("--port", type=int, default=8765)
    i.add_argument("--ip", default="10.0.0.163")
    i.set_defaults(func=cmd_init)

    s = sub.add_parser("serve")
    s.add_argument("--plain", action="store_true", help="serve plain HTTP (for tunnels)")
    s.add_argument("--port", type=int, default=None)
    s.set_defaults(func=cmd_serve)

    se = sub.add_parser("send")
    se.add_argument("op")
    se.add_argument("--text")
    se.add_argument("--device")
    se.set_defaults(func=cmd_send)

    r = sub.add_parser("results")
    r.add_argument("--n", type=int, default=20)
    r.set_defaults(func=cmd_results)

    d = sub.add_parser("devices")
    d.set_defaults(func=cmd_devices)

    args = p.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
