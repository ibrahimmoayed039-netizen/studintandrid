package com.attendance.nfc.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File

data class AuditEntry(val ts: Long, val userName: String, val action: String, val detail: String)

/** سجل تدقيق: من فعل ماذا ومتى. ملف JSON Lines محلي، الأحدث أولاً، بحد أقصى 5000 سجل. */
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
        if (list.size > MAX) { list = list.take(MAX); rewrite(list) }
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

    private fun rewrite(newestFirst: List<AuditEntry>) {
        try { file.writeText(newestFirst.reversed().joinToString("") { line(it) + "\n" }, Charsets.UTF_8) } catch (_: Exception) {}
    }

    companion object {
        const val MAX = 5000
        @Volatile private var inst: AuditStore? = null
        /** نسخة واحدة للتطبيق كله ليشاركها الخادم والواجهة. */
        fun get(ctx: Context): AuditStore = inst ?: synchronized(this) {
            inst ?: AuditStore(ctx.applicationContext).also { inst = it }
        }
    }
}
