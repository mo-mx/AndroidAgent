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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Torch control through CameraManager.setTorchMode(), with real verification.
 *
 * Facts that matter on this device (SM-F731U1 / Android 17):
 *  - setTorchMode() needs no CAMERA runtime permission (it is not a capture request); the
 *    failure modes are device support, camera-in-use, and asynchronous reporting.
 *  - Registering a TorchCallback can immediately deliver an initial event for the camera. That
 *    initial event must NOT be treated as the outcome of our request — doing so reported a
 *    torch that had actually switched on (dumpsys said "Torch for camera id 0 turned on") as
 *    DEVICE_UNAVAILABLE. Hence the [armed] flag.
 *  - The state flips asynchronously; we wait for onTorchModeChanged(cameraId, on) so that
 *    "API returned" is never mistaken for "light is on".
 */
object TorchControl {
    private const val TAG = "TorchControl"
    private const val VERIFY_TIMEOUT_MS = 4000L

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
                "torch", "这台手机没有可用的闪光灯", "no camera reports FLASH_INFO_AVAILABLE"
            )
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return ToolResult.unavailable("torch", "相机服务不可用")

        val latch = CountDownLatch(1)
        val observed = AtomicReference<Boolean?>(null)
        val unavailable = AtomicBoolean(false)
        val armed = AtomicBoolean(false)

        val callback = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                Log.i(TAG, "cb onTorchModeChanged id=$cameraId enabled=$enabled (want=${cam.id}/$on)")
                if (cameraId != cam.id) return
                observed.set(enabled)
                if (armed.get() && enabled == on) latch.countDown()
            }

            override fun onTorchModeUnavailable(cameraId: String) {
                Log.i(TAG, "cb onTorchModeUnavailable id=$cameraId")
                if (cameraId != cam.id) return
                if (armed.get()) unavailable.set(true)
            }
        }

        // A Handler is mandatory when this runs off the main thread (it does: local commands
        // execute on a worker). Passing null there throws IllegalArgumentException.
        val handler = Handler(Looper.getMainLooper())

        return try {
            cm.registerTorchCallback(callback, handler)
            armed.set(true)
            cm.setTorchMode(cam.id, on)
            // Samsung's CameraService delivered the torch callbacks ~2.6 s after the request in
            // on-device tests, so the window has to be generous. Blocking is fine: this runs on
            // a worker thread (see VoiceActivity.handleText).
            val confirmed = latch.await(VERIFY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val state = observed.get()
            Log.i(TAG, "set(on=$on) camera=${cam.id} confirmed=$confirmed observed=$state unavailable=${unavailable.get()}")
            when {
                state == on -> ToolResult.ok(
                    "torch",
                    if (on) "手电筒已打开" else "手电筒已关闭",
                    verified = true,
                    detail = "camera=${cam.id} torch=$state"
                )
                state == null && unavailable.get() -> ToolResult.unavailable(
                    "torch", "手电筒被占用，可能正在使用相机",
                    "onTorchModeUnavailable after request (camera in use)"
                )
                state != null && state != on -> ToolResult.error(
                    "torch", "手电筒没有切换成功", "requested=$on observed=$state"
                )
                else -> ToolResult(
                    ToolStatus.TIMEOUT, "torch",
                    if (on) "手电筒请求发出去了，但没等到确认" else "关闭请求发出去了，但没等到确认",
                    "no matching TorchCallback within ${VERIFY_TIMEOUT_MS}ms", null
                )
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "setTorchMode failed", e)
            ToolResult.error("torch", "相机拒绝了这个操作", e.message ?: "CameraAccessException")
        } catch (t: Throwable) {
            Log.e(TAG, "torch unexpected", t)
            ToolResult.error("torch", "手电筒调用出错", t.javaClass.simpleName)
        } finally {
            runCatching { cm.unregisterTorchCallback(callback) }
        }
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
