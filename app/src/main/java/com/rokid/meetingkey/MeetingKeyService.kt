package com.rokid.meetingkey

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * 常驻服务：持有 assistserver 连接 + 监听眼镜手势。
 *
 * 手势语义（按需求）：
 *   单击        → 开始录音
 *   连续两次单击 → 停止录音
 *   （镜腿单击 / 双指双击 也等同「单击」）
 */
class MeetingKeyService : Service() {

    companion object {
        const val TAG = "MeetingKey"
        const val ACTION_START = "com.rokid.meetingkey.START"
        const val ACTION_STOP = "com.rokid.meetingkey.STOP"
        const val ACTION_TAP = "com.rokid.meetingkey.TAP"

        private const val CHANNEL_ID = "meetingkey"
        private const val NOTIF_ID = 4312
        private const val REC_DIR = "/sdcard/Recordings"
        /** 两次单击间隔小于这个值算「连续两次」 */
        private const val DOUBLE_TAP_WINDOW_MS = 1500L

        /** 开始录音：镜腿单击、双指双击 */
        val TAP_ACTIONS = setOf(
            "com.android.action.ACTION_SPRITE_BUTTON_CLICK",
            "com.android.action.ACTION_TWO_FINGER_DOUBLE_TAP",
        )

        /** 停止录音：镜腿双击、镜腿三击（三击默认是蓝牙配对，录音中才拦截） */
        val STOP_ACTIONS = setOf(
            "com.android.action.ACTION_SPRITE_BUTTON_DOUBLE_CLICK",
            "com.android.action.ACTION_BOLON_PAIRING",
        )

        /** 只记录、不触发 */
        val OBSERVE_ACTIONS = setOf(
            "com.android.action.ACTION_SPRITE_BUTTON_LONG_PRESS",
            "com.android.action.ACTION_SPRITE_BUTTON_VERY_VERY_LONG_PRESS",
            "com.android.action.ACTION_AI_START",
            "com.android.action.ACTION_TWO_FINGER_SWIPE_FORWARD",
            "com.android.action.ACTION_TWO_FINGER_SWIPE_BACK",
            "com.android.action.ACTION_SETTINGS_KEY",
            "com.rokid.sprite.ACTION_TAKE_STATUS_CHANGED",
            "com.rokid.sprite.ACTION_LEG_STATUS_CHANGED",
        )

        private val LABELS = mapOf(
            "com.android.action.ACTION_SPRITE_BUTTON_CLICK" to "镜腿·单击",
            "com.android.action.ACTION_SPRITE_BUTTON_DOUBLE_CLICK" to "镜腿·双击",
            "com.android.action.ACTION_SPRITE_BUTTON_LONG_PRESS" to "镜腿·长按",
            "com.android.action.ACTION_SPRITE_BUTTON_VERY_VERY_LONG_PRESS" to "镜腿·超长按",
            "com.android.action.ACTION_AI_START" to "触控板·单指长按(AI)",
            "com.android.action.ACTION_TWO_FINGER_DOUBLE_TAP" to "双指·双击",
            "com.android.action.ACTION_TWO_FINGER_SWIPE_FORWARD" to "双指·前滑",
            "com.android.action.ACTION_TWO_FINGER_SWIPE_BACK" to "双指·后滑",
            "com.android.action.ACTION_SETTINGS_KEY" to "双指·长按(设置)",
            "com.rokid.sprite.ACTION_TAKE_STATUS_CHANGED" to "佩戴状态变化",
            "com.rokid.sprite.ACTION_LEG_STATUS_CHANGED" to "镜腿开合",
        )

        fun labelOf(action: String): String = LABELS[action] ?: action.substringAfterLast('.')
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var bridge: AssistBridge? = null
    private var timerJob: Job? = null
    private var watchJob: Job? = null
    private var pendingTapJob: Job? = null
    private var startedAt = 0L

    /** 录音开始前 /Recordings 里的文件集合，用来判断新文件 */
    private var beforeFiles: Set<String> = emptySet()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val label = labelOf(action)
            when {
                action in TAP_ACTIONS -> {
                    abortIfOrdered(label)
                    MeetingState.log("$label → 计数")
                    onTap()
                }
                action in STOP_ACTIONS -> {
                    if (MeetingState.recording.value) {
                        abortIfOrdered(label)
                        MeetingState.log("$label → 停止")
                        stopRecording("手势停止")
                    } else {
                        MeetingState.log("$label · 收到（未在录音）")
                    }
                }
                action in OBSERVE_ACTIONS -> {
                    abortIfOrdered(label)
                    val extra = intent.getStringExtra("glasses_take_state")
                        ?: intent.getStringExtra("glasses_leg_state")
                    MeetingState.log(if (extra == null) "$label · 收到" else "$label=$extra")
                }
                else -> MeetingState.log("$label · 收到")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startInForeground("待机")
        MeetingState.serviceRunning.value = true
        registerGestureReceiver()
        bridge = AssistBridge(this).also { it.bind() }
        MeetingState.log("服务已启动")
    }

    private fun registerGestureReceiver() {
        val filter = IntentFilter().apply {
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY
            (TAP_ACTIONS + STOP_ACTIONS + OBSERVE_ACTIONS).forEach { addAction(it) }
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    private fun abortIfOrdered(label: String) {
        if (!receiver.isOrderedBroadcast) return
        try {
            receiver.abortBroadcast()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "abortBroadcast failed for $label: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TAP -> onTap()
            ACTION_START -> if (!MeetingState.recording.value) startRecording()
            ACTION_STOP -> if (MeetingState.recording.value) stopRecording("外部指令")
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 单击 → 开始；1.5 秒内第二次单击 → 停止 */
    private fun onTap() {
        if (!MeetingState.recording.value) {
            startRecording()
            return
        }
        if (pendingTapJob?.isActive == true) {
            pendingTapJob?.cancel()
            pendingTapJob = null
            stopRecording("连点两下")
        } else {
            pendingTapJob = scope.launch {
                delay(DOUBLE_TAP_WINDOW_MS)
                MeetingState.log("（单击已忽略：录音中，请连点两下停止）")
            }
        }
    }

    private fun startRecording() {
        val b = bridge
        if (b == null || !b.isReady()) {
            MeetingState.lastError.value = "未连上 assistserver"
            MeetingState.log("无法开始：assistserver 未就绪")
            return
        }
        beforeFiles = listRecordings()
        if (!b.sendRecordCommand(AssistBridge.CMD_START)) {
            MeetingState.lastError.value = "命令发送失败"
            return
        }
        startedAt = System.currentTimeMillis()
        MeetingState.lastError.value = null
        MeetingState.recording.value = true
        MeetingState.elapsedMs.value = 0L
        MeetingState.log("会议记录 · 开始")
        startInForeground("会议记录中 " + MeetingState.elapsedText())
        startTimer()
        startWatchdog()
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (MeetingState.recording.value) {
                MeetingState.elapsedMs.value = System.currentTimeMillis() - startedAt
                notifyText("会议记录中 " + MeetingState.elapsedText())
                delay(500)
            }
        }
    }

    private fun stopRecording(reason: String) {
        val b = bridge
        if (b != null && b.isReady()) {
            b.sendRecordCommand(AssistBridge.CMD_STOP)
        }
        val durationMs = System.currentTimeMillis() - startedAt
        MeetingState.log("停止（$reason，${durationMs / 1000} 秒）")
        finishRecording()
    }

    /**
     * 看门狗：录音期间持续检查 /sdcard/Recordings 里还有没有 .tmp。
     * 连续两次都找不到，说明系统那边已经结束了录音（例如被手机 App 停掉），
     * 这里同步复位，避免界面一直卡在「记录中」。
     */
    private fun startWatchdog() {
        watchJob?.cancel()
        watchJob = scope.launch {
            delay(4000) // 给系统一点时间把 .tmp 建出来
            var misses = 0
            while (MeetingState.recording.value) {
                when (hasTempFile()) {
                    true -> misses = 0
                    false -> {
                        misses++
                        if (misses >= 2) {
                            MeetingState.log("系统已结束录音 → 同步复位")
                            finishRecording()
                            return@launch
                        }
                    }
                    null -> { /* 读不到目录，不做判断 */ }
                }
                delay(1500)
            }
        }
    }

    /** true=有 .tmp；false=确认没有；null=读不到目录（权限/延迟），不判断 */
    private fun hasTempFile(): Boolean? {
        val files = File(REC_DIR).listFiles() ?: return null
        return files.any { it.name.endsWith(".tmp") }
    }

    /** 只复位本地状态并回查落盘文件，不再发停止命令。 */
    private fun finishRecording() {
        timerJob?.cancel()
        watchJob?.cancel()
        pendingTapJob?.cancel()
        MeetingState.recording.value = false
        startInForeground("待机")
        scope.launch { pollForNewWav() }
    }

    /** assistserver 改名有延迟，轮询回查新出现的 .wav。 */
    private suspend fun pollForNewWav() {
        val known = beforeFiles.map { it.removeSuffix(".tmp") + ".wav" }.toSet()
        repeat(8) {
            delay(1500)
            val fresh = (listRecordings() - known).filter { it.endsWith(".wav") }
            if (fresh.isNotEmpty()) {
                MeetingState.lastFile.value = "$REC_DIR/" + fresh.max()
                MeetingState.log("新文件 " + fresh.max())
                return
            }
        }
        MeetingState.log("（未发现新文件，可能仍在写入）")
    }

    private fun listRecordings(): Set<String> {
        return try {
            File(REC_DIR).listFiles()?.map { it.name }?.toSet() ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        timerJob?.cancel()
        watchJob?.cancel()
        pendingTapJob?.cancel()
        bridge?.unbind()
        scope.cancel()
        MeetingState.serviceRunning.value = false
        MeetingState.log("服务已停止")
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(CHANNEL_ID, "会议记录键", NotificationManager.IMPORTANCE_MIN)
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("会议记录键")
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    private fun startInForeground(text: String) {
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun notifyText(text: String) {
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
        }
    }
}
