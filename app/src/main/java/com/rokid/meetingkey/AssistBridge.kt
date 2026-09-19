package com.rokid.meetingkey

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.util.Log

/**
 * 直连 Rokid assistserver 的 binder，转发「会议记录 / 录音」命令。
 *
 * 逆向自设备固件（Rokid Glasses 1.25.015）：
 *   服务    com.rokid.os.sprite.assistserver / com.rokid.os.sprite.assist.MasterAssistService
 *   接口    com.rokid.os.sprite.assist.server.IAssistServer
 *   方法    controlMsgJson(String packageName, String json)   [transaction 1]
 *           ⚠ 参数顺序是 (包名, JSON)，反了会被当成客户端注册
 *   命令    cmd_start_audio_record / cmd_stop_audio_record
 *   参数    AudioData.audioOpenType = "audio_no_ui"（后台录，不弹界面）
 *                                   | "audio_with_ui"（带录音界面）
 *   JSON    {"type":"cmd_start_audio_record","data":{"audioOpenType":"audio_no_ui"}}
 *
 * 注意：所有调用都走 raw Parcel 反射，因此这个 App 不依赖 Rokid 的私有 SDK。
 */
class AssistBridge(private val context: Context) {

    companion object {
        private const val TAG = "MeetingKey"
        private const val ASSIST_PKG = "com.rokid.os.sprite.assistserver"
        private const val ASSIST_SVC = "com.rokid.os.sprite.assist.MasterAssistService"
        private const val DESCRIPTOR = "com.rokid.os.sprite.assist.server.IAssistServer"
        /** 事务号来自生成的 Proxy：registerClient=1, unRegisterClient=2, controlMsgJson=3 */
        private const val TX_CONTROL_MSG_JSON = 3

        const val CMD_START = "cmd_start_audio_record"
        const val CMD_STOP = "cmd_stop_audio_record"

        const val TYPE_NO_UI = "audio_no_ui"
        const val TYPE_WITH_UI = "audio_with_ui"

        /** 是否请求「带界面」的会议记录页；false = 后台静默录。 */
        var useUi = false
    }

    private var binder: IBinder? = null
    private var bound = false

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service
            MeetingState.binderReady.value = true
            MeetingState.log("已连上 assistserver")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
            bound = false
            MeetingState.binderReady.value = false
            MeetingState.log("assistserver 断开")
        }
    }

    fun bind() {
        if (bound) return
        val i = Intent().apply {
            component = ComponentName(ASSIST_PKG, ASSIST_SVC)
        }
        try {
            bound = context.bindService(i, conn, Context.BIND_AUTO_CREATE)
            MeetingState.log(if (bound) "正在连接 assistserver…" else "bindService 返回 false")
        } catch (e: Exception) {
            Log.e(TAG, "bind failed", e)
            MeetingState.log("连接失败：${e.message}")
        }
    }

    fun unbind() {
        if (!bound) return
        runCatching { context.unbindService(conn) }
        bound = false
        binder = null
        MeetingState.binderReady.value = false
    }

    fun isReady(): Boolean = binder?.isBinderAlive == true

    /** 发送一条录音控制命令。返回是否成功送达。 */
    fun sendRecordCommand(cmd: String, withUi: Boolean = useUi): Boolean {
        val b = binder
        if (b == null || !b.isBinderAlive) {
            MeetingState.log("未连接 assistserver，命令未发送")
            return false
        }
        val openType = if (withUi) TYPE_WITH_UI else TYPE_NO_UI
        val json = """{"type":"$cmd","data":{"audioOpenType":"$openType"}}"""
        return try {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR)
                data.writeString(context.packageName)
                data.writeString(json)
                b.transact(TX_CONTROL_MSG_JSON, data, reply, 0)
                reply.readException()
            } finally {
                data.recycle()
                reply.recycle()
            }
            MeetingState.log("已发送 $cmd ($openType)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "transact failed", e)
            MeetingState.log("发送失败：${e.message}")
            false
        }
    }
}
