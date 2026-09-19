package com.rokid.meetingkey

import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 进程内共享状态。 */
object MeetingState {
    val recording = MutableStateFlow(false)
    val elapsedMs = MutableStateFlow(0L)
    val lastFile = MutableStateFlow<String?>(null)
    val lastError = MutableStateFlow<String?>(null)
    val serviceRunning = MutableStateFlow(false)
    val events = MutableStateFlow<List<String>>(emptyList())
    /** 已连上 assistserver 的 binder */
    val binderReady = MutableStateFlow(false)

    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun log(msg: String) {
        android.util.Log.i("MeetingKey", msg)
        events.value = (listOf(fmt.format(Date()) + "  " + msg) + events.value).take(8)
    }

    fun elapsedText(): String {
        val total = elapsedMs.value / 1000
        return String.format(Locale.US, "%02d:%02d", total / 60, total % 60)
    }
}
