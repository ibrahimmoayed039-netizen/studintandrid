package com.attendance.nfc.data

import android.graphics.*
import android.graphics.pdf.PdfDocument
import com.attendance.nfc.MonthRow
import java.io.OutputStream
import java.time.LocalDate

/** تقرير شهري بصيغة PDF (A4، عربي RTL) مع شعار واسم المدرسة — بدون أي مكتبة خارجية. */
object ReportPdf {
    private const val W = 595
    private const val H = 842
    private const val M = 36f
    private const val ROW_H = 22f

    fun monthly(out: OutputStream, school: String, logoPath: String, month: String, group: String, rows: List<MonthRow>) {
        val doc = PdfDocument()
        fun paint(size: Float, bold: Boolean = false, color: Int = Color.BLACK, align: Paint.Align = Paint.Align.RIGHT) =
            Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; isFakeBoldText = bold; this.color = color; textAlign = align }

        val widths = floatArrayOf(28f, 170f, 95f, 50f, 45f, 45f, 45f, 45f) // مجموعها 523 = W - 2M
        val heads = listOf("#", "اسم الطالب", "الصف", "الحصص", "حاضر", "متأخر", "غائب", "النسبة")
        val right = W - M
        fun colRange(i: Int): Pair<Float, Float> {
            val r = right - widths.take(i).sum()
            return (r - widths[i]) to r
        }
        fun fit(text: String, p: Paint, maxW: Float): String {
            if (p.measureText(text) <= maxW) return text
            var t = text
            while (t.isNotEmpty() && p.measureText("$t…") > maxW) t = t.dropLast(1)
            return "$t…"
        }
        fun cell(c: Canvas, text: String, i: Int, y: Float, p: Paint, center: Boolean) {
            val (l, r) = colRange(i)
            val s = fit(text, p, widths[i] - 8f)
            if (center) { p.textAlign = Paint.Align.CENTER; c.drawText(s, (l + r) / 2, y, p) }
            else { p.textAlign = Paint.Align.RIGHT; c.drawText(s, r - 5f, y, p) }
        }

        val headBg = Paint().apply { color = Color.parseColor("#3D4A9E") }
        val stripe = Paint().apply { color = Color.parseColor("#F1F3FB") }
        val line = Paint().apply { color = Color.parseColor("#C9CEE6"); strokeWidth = 0.6f }
        val hp = paint(12f, true, Color.WHITE)
        val bp = paint(11.5f)
        val logo = if (logoPath.isNotBlank()) try { BitmapFactory.decodeFile(logoPath) } catch (_: Exception) { null } else null

        var pageNo = 0
        var page: PdfDocument.Page? = null
        var canvas: Canvas? = null
        var y = 0f

        fun footer(c: Canvas) {
            val fp = paint(9.5f, color = Color.GRAY, align = Paint.Align.CENTER)
            c.drawText("طُبع بتاريخ ${LocalDate.now()}   •   صفحة $pageNo", W / 2f, H - 20f, fp)
        }
        fun tableHeader(c: Canvas, top: Float): Float {
            c.drawRect(M, top, W - M, top + 26f, headBg)
            heads.forEachIndexed { i, h -> cell(c, h, i, top + 17.5f, hp, center = i != 1 && i != 2) }
            return top + 26f
        }
        fun newPage() {
            page?.let { footer(canvas!!); doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            canvas = page!!.canvas
            val c = canvas!!
            if (pageNo == 1) {
                var tx = right
                if (logo != null) {
                    val box = 64f
                    val sc = box / maxOf(logo.width, logo.height)
                    val w = logo.width * sc; val h = logo.height * sc
                    c.drawBitmap(logo, null, RectF(right - w, M, right, M + h), Paint(Paint.FILTER_BITMAP_FLAG))
                    tx = right - w - 14f
                }
                c.drawText(school, tx, M + 26f, paint(22f, true, Color.parseColor("#2A3170")))
                c.drawText("التقرير الشهري للحضور", tx, M + 50f, paint(14f, false, Color.DKGRAY))
                val sub = "الشهر: $month" + if (group.isNotBlank()) "   •   المجموعة: $group" else "   •   كل الطلاب"
                c.drawText(sub, tx, M + 68f, paint(12f, false, Color.GRAY))
                c.drawLine(M, M + 80f, W - M, M + 80f, Paint().apply { color = Color.parseColor("#6D7CFF"); strokeWidth = 2f })

                val withData = rows.filter { it.total > 0 }
                val avg = if (withData.isEmpty()) 0 else withData.sumOf { it.pct } / withData.size
                val low = rows.count { it.total > 0 && it.pct < 75 }
                c.drawText("عدد الطلاب: ${rows.size}    •    متوسط الحضور: $avg%    •    دون 75%: $low طالب", right, M + 102f, paint(12f, true))
                y = tableHeader(c, M + 114f)
            } else {
                y = tableHeader(c, M)
            }
        }

        newPage()
        rows.forEachIndexed { idx, r ->
            if (y + ROW_H > H - 40f) newPage()
            val c = canvas!!
            if (idx % 2 == 1) c.drawRect(M, y, W - M, y + ROW_H, stripe)
            val base = y + 15f
            cell(c, "${idx + 1}", 0, base, paint(11f, color = Color.GRAY), true)
            cell(c, r.name, 1, base, bp, false)
            cell(c, r.grade.ifBlank { "—" }, 2, base, bp, false)
            cell(c, "${r.total}", 3, base, bp, true)
            cell(c, "${r.present}", 4, base, bp, true)
            cell(c, "${r.late}", 5, base, bp, true)
            cell(c, "${r.absent}", 6, base, bp, true)
            val col = when { r.total == 0 -> Color.GRAY; r.pct >= 90 -> Color.parseColor("#0E8F5A"); r.pct >= 75 -> Color.parseColor("#C77F0A"); else -> Color.parseColor("#D32F57") }
            cell(c, if (r.total > 0) "${r.pct}%" else "—", 7, base, paint(11.5f, true, col), true)
            y += ROW_H
            c.drawLine(M, y, W - M, y, line)
        }
        if (rows.isEmpty()) canvas!!.drawText("لا توجد بيانات لهذا الشهر", right, y + 20f, bp)
        page?.let { footer(canvas!!); doc.finishPage(it) }
        doc.writeTo(out)
        doc.close()
    }
}
