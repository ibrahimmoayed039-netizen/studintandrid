package com.attendance.nfc.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/** نسخ احتياطي تلقائي مرة واحدة كل يوم عند فتح التطبيق، مع حذف القديم منها. */
object AutoBackup {
    private val NAME = Regex("""^backup-\d{4}-\d{2}-\d{2}\.json$""")

    fun dir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "backups").apply { mkdirs() }

    fun list(ctx: Context): List<File> =
        dir(ctx).listFiles { f -> NAME.matches(f.name) }?.sortedByDescending { it.name } ?: emptyList()

    suspend fun runIfNeeded(db: AppDatabase, settings: AppSettings, ctx: Context) = withContext(Dispatchers.IO) {
        try {
            val dest = File(dir(ctx), "backup-${LocalDate.now()}.json")
            if (!dest.exists() && db.studentDao().all().isNotEmpty()) {
                dest.writeText(Backup.export(db, settings, ctx), Charsets.UTF_8)
            }
            prune(ctx, settings.backupDays)
        } catch (_: Exception) {}
    }

    fun prune(ctx: Context, keepDays: Int) {
        val files = list(ctx).sortedBy { it.name }
        val excess = files.size - keepDays.coerceAtLeast(1)
        for (i in 0 until excess) files[i].delete()
    }
}
