package com.rokid.meetingkey

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

private val GREEN = Color(0xFF00FF00)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        MeetingState.log("界面已打开")
        // 读 /sdcard/Recordings 需要存储权限（用于显示刚落盘的文件名）
        val perms = listOf(
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.RECORD_AUDIO,
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (perms.isNotEmpty()) {
            registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts
                .RequestMultiplePermissions()) { }
                .launch(perms.toTypedArray())
        }
        setContent { Screen() }
    }

    override fun onResume() {
        super.onResume()
        val i = Intent(this, MeetingKeyService::class.java)
        runCatching { ContextCompat.startForegroundService(this, i) }
            .onFailure { MeetingState.log("服务启动被拒：" + (it.message ?: "未知")) }
    }

    /**
     * 触控板：单指轻点 = KEYCODE_ENTER → 开始／计数；
     * 单指双击 = KEYCODE_BACK → 录音中直接停止，否则维持系统返回。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_UP || event.repeatCount != 0) {
            return super.dispatchKeyEvent(event)
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER -> {
                MeetingState.log("触控板·单击 → 计数")
                sendToService(MeetingKeyService.ACTION_TAP)
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (MeetingState.recording.value) {
                    MeetingState.log("触控板·双击 → 停止")
                    sendToService(MeetingKeyService.ACTION_STOP)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun sendToService(action: String) {
        val i = Intent(this, MeetingKeyService::class.java).setAction(action)
        runCatching { ContextCompat.startForegroundService(this, i) }
            .onFailure { MeetingState.log("触发被拒：" + (it.message ?: "未知")) }
    }
}

@Composable
private fun Screen() {
    val recording by MeetingState.recording.collectAsState()
    val elapsed by MeetingState.elapsedMs.collectAsState()
    val error by MeetingState.lastError.collectAsState()
    val running by MeetingState.serviceRunning.collectAsState()
    val ready by MeetingState.binderReady.collectAsState()

    val s = elapsed / 1000
    val timeText = String.format(java.util.Locale.US, "%02d:%02d", s / 60, s % 60)

    // 只在最下面两行显示，尽量用符号：
    //   ○ 待机 / ● 录音中 + 计时
    //   ▶ 点一下开始   ■■ 连点两下停止
    //   末尾标记：! 出错   … 未连上服务
    val line1 = if (recording) "● $timeText" else "○"
    val line2 = buildString {
        append(if (recording) "■■ 停止" else "▶ 开始")
        when {
            error != null -> append("   !")
            !running || !ready -> append("   …")
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(start = 16.dp, end = 16.dp, bottom = 54.dp),
            verticalArrangement = Arrangement.Bottom
        ) {
            T(line1, 12, FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            T(line2, 11)
        }
    }
}

@Composable
private fun T(text: String, size: Int, weight: FontWeight = FontWeight.Normal) {
    Text(
        text,
        color = GREEN,
        fontSize = size.sp,
        fontWeight = weight,
        fontFamily = FontFamily.Monospace,
        lineHeight = (size + 3).sp
    )
}
