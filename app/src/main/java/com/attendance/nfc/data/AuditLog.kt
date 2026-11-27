package com.attendance.nfc.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File

data class AuditEntry(val ts: Long, val userName: String, val action: String, val detail: String)

/** سجل تدقيق: من فعل ماذا ومتى. ملف JSON Lines محلي، الأحدث أولاً، بحد أقصى 5000 سجل، ويُحذف تلقائياً ما مضى عليه أكثر من 3 أيام. */
class AuditStore(ctx: Context) {
    private val file = File(ctx.filesDir, "audit.jsonl")
    private val _flow = MutableStateFlow(load())
    val flow: StateFlow<List<AuditEntry>> = _flow

    private fun load(): List<AuditEntry> = try {
        if (!file.exists()) emptyList() else file.readLines().mapNotNull { line ->
            try {
                val o = JSONObject(line)
                AuditEntry(o.getLong("ts"), o.optString("u"), o.optString("a"), o.optString("d"))
            } catch (_: Exception) { null }
        }.reversed()
    } catch (_: Exception) { emptyList() }

    private fun line(e: AuditEntry) =
        JSONObject().put("ts", e.ts).put("u", e.userName).put("a", e.action).put("d", e.detail).toString()

    @Synchronized
    fun add(user: String, action: String, detail: String = "") {
        val e = AuditEntry(System.currentTimeMillis(), user, action, detail)
        try { file.appendText(line(e) + "\n", Charsets.UTF_8) } catch (_: Exception) {}
        var list = listOf(e) + _flow.value
        val cutoff = System.currentTimeMillis() - RETAIN_DAYS * 86_400_000L
        if (list.size > MAX || list.last().ts < cutoff) { list = list.take(MAX).filter { it.ts >= cutoff }; rewrite(list) }
        _flow.value = list
    }

    @Synchronized
    fun purgeOlderThan(days: Int): Int {
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        val keep = _flow.value.filter { it.ts >= cutoff }
        val removed = _flow.value.size - keep.size
        rewrite(keep)
        _flow.value = keep
        return removed
    }

    /** يحذف كل السجلات يدوياً. */
    @Synchronized
    fun clearAll(): Int {
        val n = _flow.value.size
        _flow.value = emptyList()
        try { file.writeText("", Charsets.UTF_8) } catch (_: Exception) {}
        return n
    }

    private fun rewrite(newestFirst: List<AuditEntry>) {
        try { file.writeText(newestFirst.reversed().joinToString("") { line(it) + "\n" }, Charsets.UTF_8) } catch (_: Exception) {}
    }

    companion object {
        const val MAX = 5000
        const val RETAIN_DAYS = 3
        @Volatile private var inst: AuditStore? = null
        /** نسخة واحدة للتطبيق كله ليشاركها الخادم والواجهة. */
        fun get(ctx: Context): AuditStore = inst ?: synchronized(this) {
            inst ?: AuditStore(ctx.applicationContext).also { it.purgeOlderThan(RETAIN_DAYS); inst = it }
        }
    }
}
