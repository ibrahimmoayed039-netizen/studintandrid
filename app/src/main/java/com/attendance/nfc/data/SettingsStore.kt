package com.attendance.nfc.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class AppSettings(
    val schoolName: String = "حضور الطلبة",
    val autoSchedule: Boolean = false,
    val lectureMin: Int = 45,
    val breakMin: Int = 10,
    val defaultLate: Int = 10,
    val backupDays: Int = 30,
    val logoPath: String = "",
    /** فتح شاشة استقبال البطاقات (ملء الشاشة) تلقائياً لحظة بدء كل حصة. */
    val autoReceive: Boolean = true,
    /** تسجيل خروج تلقائي عند انتهاء الحصة (يدوياً أو تلقائياً) لمنع العبث بالبيانات. */
    val autoLogout: Boolean = true
)

/** إعدادات محفوظة محلياً (SharedPreferences) وتبقى بعد إغلاق التطبيق. */
class SettingsStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _flow = MutableStateFlow(read())
    val flow: StateFlow<AppSettings> = _flow

    private fun read() = AppSettings(
        schoolName = sp.getString("school", "حضور الطلبة") ?: "حضور الطلبة",
        autoSchedule = sp.getBoolean("auto", false),
        lectureMin = sp.getInt("lecture", 45),
        breakMin = sp.getInt("break", 10),
        defaultLate = sp.getInt("late", 10),
        backupDays = sp.getInt("backupDays", 30),
        logoPath = sp.getString("logo", "") ?: "",
        autoReceive = sp.getBoolean("autoReceive", true),
        autoLogout = sp.getBoolean("autoLogout", true)
    )

    fun save(v: AppSettings) {
        sp.edit()
            .putString("school", v.schoolName)
            .putBoolean("auto", v.autoSchedule)
            .putInt("lecture", v.lectureMin)
            .putInt("break", v.breakMin)
            .putInt("late", v.defaultLate)
            .putInt("backupDays", v.backupDays)
            .putString("logo", v.logoPath)
            .putBoolean("autoReceive", v.autoReceive)
            .putBoolean("autoLogout", v.autoLogout)
            .apply()
        _flow.value = v
    }
}
