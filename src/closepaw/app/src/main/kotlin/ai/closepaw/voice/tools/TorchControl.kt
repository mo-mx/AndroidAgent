package ai.closepaw.voice.tools

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Torch control through CameraManager.setTorchMode(), with real verification.
 *
 * Facts that matter on this device (SM-F731U1 / Android 17):
 *  - setTorchMode() needs no CAMERA runtime permission (it is not a capture request); the
 *    failure modes are device support, camera-in-use, and asynchronous reporting.
 *  - Registering a TorchCallback can immediately deliver an initial event for the camera. That
 *    initial event must NOT be treated as the outcome of our request — doing so reported a
 *    torch that had actually switched on (dumpsys said "Torch for camera id 0 turned on") as
 *    DEVICE_UNAVAILABLE. Hence the baseline wait in [set] and [RequestTracker]: events before the
 *    request are the baseline, and only a matching post-request event counts as confirmation.
 *  - The state flips asynchronously; we wait for onTorchModeChanged(cameraId, on) so that
 *    "API returned" is never mistaken for "light is on".
 */
object TorchControl {
    private const val TAG = "TorchControl"
    private const val VERIFY_TIMEOUT_MS = 4000L

    /**
     * How long to wait for the registration event that reports the *current* torch mode.
     *
     * That event is the baseline, not the result. Waiting for it (bounded) before issuing the
     * request is what makes "already on" distinguishable from "this request turned it on"; if it
     * never arrives we still proceed, and the verdict loses attribution (reports "not confirmed").
     */
    private const val BASELINE_TIMEOUT_MS = 1200L

    /** Must equal `Target.TORCH.name.lowercase()`, which is the identity the parser declares. */
    private const val IDENTITY = "torch"

    data class Camera(val id: String, val hasFlash: Boolean)

    fun findTorchCamera(ctx: Context): Camera? {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null
        return try {
            cm.cameraIdList.mapNotNull { id ->
                val ch = cm.getCameraCharacteristics(id)
                val flash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                val facing = ch.get(CameraCharacteristics.LENS_FACING)
                Triple(id, flash, facing)
            }.filter { it.second }
                .sortedBy { if (it.third == CameraCharacteristics.LENS_FACING_BACK) 0 else 1 }
                .firstOrNull()
                ?.let { Camera(it.first, true) }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "camera enumeration failed", e)
            null
        }
    }

    fun set(ctx: Context, on: Boolean): ToolResult {
        val cam = findTorchCamera(ctx)
            ?: return ToolResult.unavailable(
                "torch", "这台手机没有可用的闪光灯", "no camera reports FLASH_INFO_AVAILABLE",
                dispatched = false,
            )
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return ToolResult.unavailable("torch", "相机服务不可用", dispatched = false)

        val tracker = RequestTracker(cam.id, on)
        val baselineLatch = CountDownLatch(1)
        val confirmLatch = CountDownLatch(1)

        val callback = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                Log.i(TAG, "cb onTorchModeChanged id=$cameraId enabled=$enabled (want=${cam.id}/$on)")
                if (cameraId != cam.id) return
                val wasRequested = tracker.requested
                val confirming = tracker.onModeChanged(cameraId, enabled)
                if (!wasRequested) baselineLatch.countDown()
                if (confirming) confirmLatch.countDown()
            }

            override fun onTorchModeUnavailable(cameraId: String) {
                Log.i(TAG, "cb onTorchModeUnavailable id=$cameraId")
                if (cameraId != cam.id) return
                tracker.onUnavailable(cameraId)
            }
        }

        // A Handler is mandatory when this runs off the main thread (it does: local commands
        // execute on a worker). Passing null there throws IllegalArgumentException.
        val handler = Handler(Looper.getMainLooper())

        // Set as soon as `setTorchMode` returns: from that moment the request is out, whatever the
        // callback does (round 44). A throwable raised before it never issued anything.
        var issued = false

        return try {
            cm.registerTorchCallback(callback, handler)
            // The registration event reports the *current* mode; it is not the result of a request
            // we have not issued yet. Waiting for it first is what keeps a still-in-flight initial
            // event from landing after setTorchMode and being graded as our outcome.
            baselineLatch.await(BASELINE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            tracker.markRequested()
            cm.setTorchMode(cam.id, on)
            issued = true
            // Samsung's CameraService delivered the torch callbacks ~2.6 s after the request in
            // on-device tests, so the window has to be generous. Blocking is fine: this runs on
            // a worker thread (see VoiceActivity.handleText).
            val confirmed = confirmLatch.await(VERIFY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            Log.i(
                TAG,
                "set(on=$on) camera=${cam.id} confirmed=$confirmed post=${tracker.postState} " +
                    "baseline=${tracker.baseline} unavailable=${tracker.unavailable}"
            )
            outcome(
                on = on, cameraId = cam.id, observed = tracker.postState,
                unavailable = tracker.unavailable, confirmed = confirmed && tracker.confirmed,
                baseline = tracker.baseline, baselinePreRequest = tracker.baselinePreRequest,
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "setTorchMode failed", e)
            ToolResult(
                ToolStatus.EXECUTION_ERROR, "torch", "相机拒绝了这个操作",
                e.message ?: "CameraAccessException", false, dispatched = false,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "torch unexpected", t)
            ToolResult(
                ToolStatus.EXECUTION_ERROR, "torch", "手电筒调用出错",
                t.javaClass.simpleName, false, dispatched = issued,
            )
        } finally {
            runCatching { cm.unregisterTorchCallback(callback) }
        }
    }

    /**
     * Watches one camera's torch callback for one request and decides *which* events belong to it.
     *
     * Split out of [set] because the CameraManager plumbing needs a real Looper while this
     * attribution can be exercised on the JVM — and the attribution is where a registration event
     * gets mis-sold as a result.
     */
    internal class RequestTracker(private val cameraId: String, private val on: Boolean) {
        /** State reported before the request was issued (the registration event). */
        @Volatile var baseline: Boolean? = null
            private set

        /** State reported by an event that arrived after the request. */
        @Volatile var postState: Boolean? = null
            private set

        @Volatile var unavailable: Boolean = false
            private set

        @Volatile var requested: Boolean = false
            private set

        /**
         * Has this callback ever reported anything? The *first* report a fresh `TorchCallback`
         * receives is, by the framework's contract, the camera's current mode at registration —
         * not the outcome of a request. Round 46 review P1/P2: when the baseline window expired
         * with no event, `set()` marked the request and issued `setTorchMode` before that report
         * arrived, so it was graded as post-request evidence, `confirmLatch` released, and the
         * caller reported "手电筒已打开" plus an undoable ENABLE/TORCH for a torch it never touched.
         */
        @Volatile var sawReport: Boolean = false
            private set

        /**
         * Was the first report seen *before* the request was issued?
         *
         * Round 48 review P1: the tracker used to promote *any* first event to baseline, however
         * late it arrived. A registration event that is delayed past `markRequested()` (or dropped
         * and replaced by the post-request event) then became a "baseline" that was really our own
         * request's state, so the next matching event confirmed it and an undoable memory was
         * recorded for a change nobody could attribute. A baseline is only a baseline when it
         * predates the request; without that the verdict must stay unproven.
         */
        @Volatile var baselinePreRequest: Boolean = true
            private set

        val confirmed: Boolean get() = postState == on

        fun markRequested() {
            requested = true
        }

        /** @return true when this event is the post-request state the caller asked for. */
        fun onModeChanged(id: String, enabled: Boolean): Boolean {
            if (id != cameraId) return false
            // Before the request is issued, an event is only a report of the *current* mode —
            // typically the initial event CameraService delivers on register. Recording it as the
            // post-request state made "it was already on" look like "I turned it on".
            if (!sawReport) {
                // However late it lands (after markRequested, even after setTorchMode), the first
                // report describes the mode that existed before this request: keep it as baseline.
                sawReport = true
                baseline = enabled
                // Only a report that predates the request can say what the camera looked like
                // before we asked it to change (round 48 review P1).
                baselinePreRequest = !requested
                return false
            }
            if (!requested) return false
            postState = enabled
            return enabled == on
        }

        fun onUnavailable(id: String) {
            if (id != cameraId) return
            // Only meaningful once we have asked for something; before that it says nothing about
            // our request.
            if (!requested) return
            unavailable = true
        }
    }

    /**
     * The verdict for one torch request, from the callback evidence [set] collected.
     *
     * Split out because the plumbing (CameraManager callback + latch) needs a real Looper and
     * cannot run on the JVM, while this decision can — and the decision is where the promise is
     * made or dropped.
     *
     * Round 35 — the evidence has to be *attributed*: [confirmed] is "an event that arrived after
     * the request reported the state we asked for", [baseline] is what the camera reported before
     * the request (null when no event arrived in the baseline window). An [observed] value alone is
     * not enough, because the registration event reports the current mode whether or not this
     * request did anything.
     */
    internal fun outcome(
        on: Boolean,
        cameraId: String,
        observed: Boolean?,
        unavailable: Boolean,
        confirmed: Boolean,
        baseline: Boolean?,
        baselinePreRequest: Boolean = true,
    ): ToolResult =
        when {
            // A camera that went unavailable after the request is not a success, whatever an
            // earlier callback said.
            unavailable -> ToolResult.unavailable(
                "torch", "手电筒被占用，可能正在使用相机",
                "onTorchModeUnavailable after request (camera in use)",
                dispatched = true,
            )
            // A post-request event named this camera and reported the state we asked for: that is
            // evidence of the promised state, so it has to be reported as such. Until round 34 it
            // was not, and `TerminalState` — which needs structured evidence for `on:TORCH` /
            // `off:TORCH` — hedged a torch that had really come on into "请求已经发出，但没能确认结果".
            confirmed && observed == on && baseline != null && baselinePreRequest -> ToolResult.ok(
                "torch",
                if (on) "手电筒已打开" else "手电筒已关闭",
                verified = true,
                detail = "camera=$cameraId torch=$observed baseline=$baseline attributed=post-request",
                observed = ObservedState(
                    identity = IDENTITY,
                    state = if (on) "on" else "off",
                    source = "camera2:torch-callback",
                ),
                dispatched = true,
            )
            observed != null && observed != on -> ToolResult(
                ToolStatus.EXECUTION_ERROR, "torch", "手电筒没有切换成功",
                "requested=$on observed=$observed", false, dispatched = true,
            )
            else -> ToolResult(
                ToolStatus.TIMEOUT, "torch",
                if (on) "手电筒请求发出去了，但没等到确认" else "关闭请求发出去了，但没等到确认",
                "no attributed post-request TorchCallback matching requested=$on within" +
                    " ${VERIFY_TIMEOUT_MS}ms (baseline=$baseline baselinePreRequest=$baselinePreRequest" +
                    " post=$observed" + (if (baseline != null && !baselinePreRequest) " late-baseline" else "") + ")",
                null,
                dispatched = true,
            )
        }

    // ── process-wide state watcher ──
    // There is no synchronous "is the torch on?" API. A single long-lived callback fills this
    // cache, so "手电筒开着吗" can be answered from the last reported state instead of guessing.
    private val stateCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    @Volatile private var watcherStarted = false

    fun startWatcher(ctx: Context) {
        if (watcherStarted) return
        val cm = ctx.applicationContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        val cb = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                stateCache[cameraId] = enabled
                Log.i(TAG, "watcher: camera $cameraId torch=$enabled")
            }

            override fun onTorchModeUnavailable(cameraId: String) {
                stateCache.remove(cameraId)
            }
        }
        runCatching {
            cm.registerTorchCallback(cb, Handler(Looper.getMainLooper()))
            watcherStarted = true
            Log.i(TAG, "torch watcher registered")
        }.onFailure { Log.w(TAG, "watcher registration failed", it) }
    }

    /**
     * Last state reported by the framework. On a cold process the cache is empty, so this
     * actively probes: registering a callback makes CameraService report the current mode.
     * Without this, a freshly started process answered "不清楚状态" — useless for a query.
     */
    fun knownState(ctx: Context): Boolean? {
        startWatcher(ctx)
        val cam = findTorchCamera(ctx) ?: return null
        stateCache[cam.id]?.let { return it }

        val cm = ctx.applicationContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return null
        val latch = CountDownLatch(1)
        val cb = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                stateCache[cameraId] = enabled
                if (cameraId == cam.id) latch.countDown()
            }
        }
        return try {
            cm.registerTorchCallback(cb, Handler(Looper.getMainLooper()))
            latch.await(2500, TimeUnit.MILLISECONDS)
            stateCache[cam.id]
        } catch (t: Throwable) {
            Log.w(TAG, "torch state probe failed", t)
            null
        } finally {
            runCatching { cm.unregisterTorchCallback(cb) }
        }
    }
}
