package ai.closepaw.voice

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import ai.closepaw.BuildConfig
import ai.closepaw.voice.tools.LocalCapabilities
import java.io.File

/**
 * Voice entry point — the thing the side key / launcher icon opens.
 *
 * Offline-first by design: if the local sherpa-onnx ASR model is installed, audio never leaves
 * the device and neither Google's recogniser nor DashScope is contacted. Simple commands
 * (battery, time, torch, Wi-Fi, open settings/camera, stop) are answered by
 * [LocalCommandRouter] without any model at all; anything else is handed to the agent.
 *
 * Re-wake semantics (the bug this fixes): the activity is `singleTask`, so a second side-key
 * press lands in [onNewIntent], not in a new instance. Every wake therefore calls
 * [beginSession], which bumps [VoiceSession.sessionId], hard-stops TTS (engine stop + queue
 * flush + focus release), aborts the in-flight recorder and only then starts listening. Every
 * asynchronous callback captures the id it started under and is dropped if the id is stale, so
 * a previous turn can never answer, speak into, or record over a new one.
 */
class VoiceActivity : Activity() {

    companion object {
        private const val TAG = "VoiceActivity"
        const val EXTRA_FAKE_TEXT = "fake_text"
        const val EXTRA_WAV_TEST = "wav_test"
        private const val MAX_TURNS = 6
        private const val SESSION_BUDGET_MS = 45_000L
    }

    private lateinit var statusView: TextView
    private lateinit var hintView: TextView
    private val main = Handler(Looper.getMainLooper())
    private var busy = false
    private var turns = 0
    private var closed = false
    private var sessionId: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        // UI automation (clock panels, switch rows) needs a live window: without this the screen
        // dropped to AOD ~6 s into a session and every node lookup returned null.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(56, 96, 56, 96)
            setBackgroundColor(Color.parseColor("#F2101014"))
        }
        statusView = TextView(this).apply {
            text = "准备中…"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        hintView = TextView(this).apply {
            text = "离线语音：电量 / 手电筒 / 打开设置 / 退出"
            setTextColor(Color.parseColor("#B0B0B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 0)
        }
        root.addView(statusView, ViewGroup.LayoutParams(-1, -2))
        root.addView(hintView, ViewGroup.LayoutParams(-1, -2))
        setContentView(root)

        TtsSpeaker.init(this)
        val tuning = VoiceTuning.load(filesDir)
        val ext = getExternalFilesDir(null) ?: filesDir
        if (tuning.dump) VoiceRecorder.setDumpDir(File(ext, "asr_debug"))

        beginSession("onCreate")
    }

    /** Second side-key press while the voice UI already exists. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Log.i(TAG, "onNewIntent → re-wake")
        beginSession("re-wake")
        setShowWhenLocked(true)
        setTurnScreenOn(true)
    }

    /**
     * Start a brand-new voice session: kill everything from the previous one first.
     * Order matters — TTS must be silent and confirmed stopped *before* the mic opens,
     * otherwise the tail of the last answer is recorded as the new command.
     */
    private fun beginSession(reason: String) {
        sessionId = VoiceSession.beginWake(reason)
        val t0 = System.currentTimeMillis()

        VoiceRecorder.cancel()
        val silent = TtsSpeaker.stopAndAwait(500)
        busy = false
        turns = 0
        Log.i(TAG, "beginSession($reason): ttsSilent=$silent after ${System.currentTimeMillis() - t0}ms")

        // Hard session budget: a wake-up can never hang forever waiting for speech or a tool.
        main.postDelayed({
            if (VoiceSession.isCurrent(sessionId) && !closed) {
                Log.w(TAG, "session #$sessionId watchdog fired, closing")
                VoiceRecorder.cancel()
                TtsSpeaker.stop()
                VoiceSession.finish("watchdog timeout")
                if (!isFinishing) finish()
            }
        }, SESSION_BUDGET_MS)

        // Consume one-shot debug extras: if the system recreates this activity it must not replay
        // the previous command (observed: a stale "设置8点闹钟" ran twice after recreation).
        val wavPath = if (BuildConfig.DEBUG) intent?.getStringExtra(EXTRA_WAV_TEST) else null
        if (!wavPath.isNullOrBlank()) {
            intent?.removeExtra(EXTRA_WAV_TEST)
            runWavTest(File(wavPath), sessionId)
            return
        }
        if (BuildConfig.DEBUG && intent?.getBooleanExtra("parse_only", false) == true) {
            val txt = intent?.getStringExtra(EXTRA_FAKE_TEXT) ?: ""
            val tasks = ai.closepaw.voice.semantics.SemanticParser.parse(txt)
            val verdicts = tasks.map { t ->
                val v = ai.closepaw.voice.semantics.CapabilityGuard.check(t)
                "${t.describe()}|ok=${v.ok}|${v.reason}"
            }
            Log.i(TAG, "PARSEONLY \"$txt\" → ${verdicts.joinToString(" ;; ")}")
            finish()
            return
        }

        val fake = if (BuildConfig.DEBUG) intent?.getStringExtra(EXTRA_FAKE_TEXT) else null
        if (!fake.isNullOrBlank()) {
            intent?.removeExtra(EXTRA_FAKE_TEXT)
            setStatus("（调试注入）$fake")
            handleText(fake, fromVoice = false, sid = sessionId)
            return
        }
        startTurn(sessionId)
    }

    override fun onDestroy() {
        closed = true
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        VoiceRecorder.cancel()
        VoiceSession.finish("activity destroyed")
        super.onDestroy()
    }

    // ── one listening turn ──

    private fun startTurn(sid: Long) {
        if (closed || busy) return
        if (!VoiceSession.isCurrent(sid)) return
        busy = true

        // Belt and braces: even if something else started speaking, never record into TTS.
        if (TtsSpeaker.isSpeaking()) {
            setStatus("等我一下…")
            main.postDelayed({
                busy = false
                startTurn(sid)
            }, 350)
            return
        }

        val asrRoot = getExternalFilesDir(null) ?: filesDir
        if (!OfflineAsr.isInstalled(asrRoot)) {
            setStatus("未安装离线语音模型")
            hintView.text = "把模型放到 ${OfflineAsr.modelDir(asrRoot).absolutePath}"
            busy = false
            VoiceSession.setState(sid, VoiceSession.State.SPEAKING, "no model")
            TtsSpeaker.speak("没有安装离线语音模型，无法离线识别", interrupt = true)
            return
        }

        val tuning = VoiceTuning.load(filesDir)
        VoiceSession.setState(sid, VoiceSession.State.LISTENING, "mic open")
        setStatus("正在听…（离线识别）")
        VoiceRecorder.record(
            maxMs = tuning.maxMs,
            silenceMs = tuning.silenceMs,
            rmsThreshold = tuning.rms,
            onLevel = { lvl ->
                val pct = (VoiceRecorder.levelToUi(lvl) * 100).toInt()
                if (pct > 8) main.post { hintView.text = "音量 $pct%" }
            },
            onDone = { res ->
                if (closed || !VoiceSession.isCurrent(sid)) {
                    Log.i(TAG, "drop stale recorder result (${res.reason}) for #$sid")
                    busy = false
                    return@record
                }
                if (res.reason == "cancelled") {
                    busy = false
                    return@record
                }
                if (res.samples.isEmpty()) {
                    // single-turn: no retry loop, close cleanly so the mic is released
                    busy = false
                    main.post {
                        if (!VoiceSession.isCurrent(sid)) return@post
                        setStatus("没有听到说话")
                        speakAndClose(sid, "没有听到说话")
                    }
                    return@record
                }
                VoiceSession.setState(sid, VoiceSession.State.RECOGNIZING, "${res.samples.size} samples")
                setStatus("识别中…（离线）")
                Thread({
                    val text = OfflineAsr.transcribe(asrRoot, res.samples)
                    Log.i(TAG, "asr #$sid: \"$text\"")
                    main.post {
                        if (closed || !VoiceSession.isCurrent(sid)) {
                            busy = false
                            return@post
                        }
                        if (text.isBlank()) {
                            setStatus("没听清")
                            busy = false
                            speakAndClose(sid, "没听清，请再说一次")
                        } else {
                            handleText(text, fromVoice = true, sid = sid)
                        }
                    }
                }, "asr-decode").start()
            }
        )
    }

    // ── routing ──

    private fun handleText(text: String, fromVoice: Boolean, sid: Long) {
        if (!VoiceSession.isCurrent(sid)) {
            Log.i(TAG, "drop stale text for #$sid: $text")
            return
        }
        Log.i(TAG, "heard(#$sid): $text (voice=$fromVoice)")
        setStatus("「$text」")
        VoiceSession.setState(sid, VoiceSession.State.EXECUTING, text.take(24))

        // One utterance → N tasks, executed in order on a worker thread (torch/volume verification
        // blocks), then one merged answer, then the session closes. No continuous conversation.
        Thread({
            val speech = try {
                runPlan(text, sid)
            } catch (t: Throwable) {
                Log.e(TAG, "plan execution failed", t)
                "这个指令处理出错"
            }
            main.post {
                if (closed || !VoiceSession.isCurrent(sid)) return@post
                speakAndClose(sid, speech)
            }
        }, "plan-exec").start()
    }

    /** Semantic pipeline: parse → validate → execute → verify → merge. */
    private fun runPlan(text: String, sid: Long): String {
        val parsed = ai.closepaw.voice.semantics.SemanticParser.parse(text)
        Log.i(TAG, "semantic: " + parsed.joinToString(" | ") { it.describe() })
        if (parsed.none { it.target != ai.closepaw.voice.semantics.Target.UNKNOWN }) {
            // Nothing the device layer understands: cloud model when reachable, else say so.
            return handleUnknown(text, sid)
        }
        return ai.closepaw.voice.semantics.SemanticRunner.run(this, text, sid)
    }

    /** Legacy keyword planner (kept only as a reference while the semantic layer is validated). */
    private fun legacyPlan(text: String, sid: Long): String {
        val tasks = IntentPlanner.plan(text)
        Log.i(TAG, "plan(#$sid) for \"$text\": ${tasks.joinToString { it.kind.name + "/" + it.raw }}")

        val deviceTasks = tasks.filter { it.kind != IntentPlanner.Kind.UNKNOWN }
        // "如果没开就告诉我" is a modifier on the preceding query, not a task of its own — it must
        // never be routed to the cloud model.
        val unknown = tasks.filter { it.kind == IntentPlanner.Kind.UNKNOWN && !it.conditional }
        val parts = ArrayList<String>()

        if (deviceTasks.isNotEmpty()) {
            parts.add(IntentPlanner.executeAll(this, deviceTasks, sid))
        }
        if (unknown.isNotEmpty()) {
            parts.add(handleUnknown(unknown.joinToString("，") { it.raw }, sid))
        }
        return parts.filter { it.isNotBlank() }.joinToString("，")
    }

    /**
     * Anything the device layer cannot do. Only then is the cloud model considered — and only
     * after confirming the network actually validates, so a dead VPN or an expired quota cannot
     * turn into an indefinite wait or a vague "交给助手处理" that never runs.
     */
    private fun handleUnknown(text: String, sid: Long): String {
        if (!VoiceSession.isCurrent(sid)) return ""
        val online = LocalCapabilities.onlineNow(this)
        if (!online) {
            Log.i(TAG, "offline, cannot route: $text")
            return "这句需要联网才能处理，现在没有网络"
        }
        val ok = try {
            VoiceGoalDispatcher.dispatch(this, text)
        } catch (t: Throwable) {
            Log.e(TAG, "dispatch failed", t)
            false
        }
        Log.i(TAG, "dispatch to agent ok=$ok for: $text")
        return if (ok) "这句比较复杂，已经交给在线助手处理" else "在线助手启动失败，这条没有执行"
    }

    /** Speak the merged answer, wait for it to finish, then close the session and free audio. */
    private fun speakAndClose(sid: Long, speech: String) {
        VoiceSession.setState(sid, VoiceSession.State.SPEAKING, speech.take(20))
        setStatus(speech)
        TtsSpeaker.speak(speech, interrupt = true)
        busy = false
        Thread({
            // wait for the engine to go idle, but never longer than the speech budget
            val deadline = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < deadline && TtsSpeaker.isSpeaking()) {
                Thread.sleep(80)
            }
            main.post {
                if (!VoiceSession.isCurrent(sid)) return@post
                VoiceSession.finish("single-turn done")
                VoiceRecorder.cancel()
                if (!isFinishing) finish()
            }
        }, "close").start()
    }

    /** Debug/benchmark entry: transcribe a WAV file with the on-device model and log it. */
    private fun runWavTest(file: File, sid: Long) {
        setStatus("测试：${file.name}")
        statusView.postDelayed({
            Thread({
                val t0 = System.currentTimeMillis()
                val samples = WavIo.read(file)
                val text = if (samples == null) "<wav-error>" else OfflineAsr.transcribe(
                    getExternalFilesDir(null) ?: filesDir, samples
                )
                val ms = System.currentTimeMillis() - t0
                Log.i(TAG, "WAVTEST ${file.name}|${ms}ms|$text")
                main.post {
                    if (!VoiceSession.isCurrent(sid)) return@post
                    setStatus("${file.name} → $text")
                    busy = false
                    if (!isFinishing) finish()
                }
            }, "wavtest").start()
        }, 300)
    }

    private fun setStatus(t: String) {
        main.post { statusView.text = t }
    }
}
