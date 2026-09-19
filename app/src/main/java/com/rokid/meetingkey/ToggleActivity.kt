package com.rokid.meetingkey

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * 无界面的一键触发入口（可当桌面快捷方式 / adb 自动化）：
 *   adb shell am start -n com.rokid.meetingkey/.ToggleActivity
 *
 * 单击 → 开始；若已在录音中，1.5 秒内再触发一次 → 停止。
 */
class ToggleActivity : Activity() {

    override fun onResume() {
        super.onResume()
        val i = Intent(this, MeetingKeyService::class.java).setAction(MeetingKeyService.ACTION_TAP)
        runCatching { ContextCompat.startForegroundService(this, i) }
            .onFailure { MeetingState.log("快捷触发被拒：" + (it.message ?: "未知")) }
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 200)
    }
}
