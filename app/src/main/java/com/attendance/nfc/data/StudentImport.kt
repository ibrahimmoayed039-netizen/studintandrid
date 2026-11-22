package com.attendance.nfc.data

import android.content.Context
import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream

/** استيراد الطلاب من ملف Excel (.xlsx) أو CSV: أعمدة الاسم والصف ورقم البطاقة. */
object StudentImport {
    data class Row(val name: String, val grade: String, val cardUid: String)

    private val NAME_KEYS = listOf("اسم الطالب", "الاسم", "اسم", "name", "student name", "student")
    private val GRADE_KEYS = listOf("الصف", "الصف / الشعبة", "الشعبة", "grade", "class", "section")
    private val UID_KEYS = listOf("رقم البطاقة", "البطاقة", "uid", "card", "card uid", "cardid", "card id")

    fun parse(ctx: Context, uri: Uri): List<Row> {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("تعذّر قراءة الملف")
        val isZip = bytes.size > 3 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        val isOldXls = bytes.size > 3 && bytes[0] == 0xD0.toByte() && bytes[1] == 0xCF.toByte()
        if (isOldXls) throw IllegalArgumentException("صيغة xls القديمة غير مدعومة. احفظ الملف كـ xlsx أو CSV")
        val table = if (isZip) xlsx(bytes) else csv(decode(bytes))
        return toRows(table)
    }

    private fun toRows(table: List<List<String>>): List<Row> {
        if (table.isEmpty()) return emptyList()
        val header = table[0].map { it.trim().lowercase() }
        fun idx(keys: List<String>) = header.indexOfFirst { it in keys }
        var ni = idx(NAME_KEYS); var gi = idx(GRADE_KEYS); var ui = idx(UID_KEYS)
        var start = 1
        if (ni < 0) { ni = 0; gi = 1; ui = 2; start = 0 } // بدون صف عناوين: الاسم، الصف، البطاقة
        fun cell(r: List<String>, i: Int) = if (i in r.indices) r[i].trim() else ""
        return table.drop(start).map { r ->
            Row(cell(r, ni), cell(r, gi), cell(r, ui).uppercase().filter { it.isLetterOrDigit() })
        }.filter { it.name.isNotBlank() }
    }

    // ---------- CSV ----------
    private fun decode(b: ByteArray): String {
        var bytes = b
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
            bytes = bytes.copyOfRange(3, bytes.size)
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            String(bytes, charset("windows-1256"))
        }
    }

    private fun csv(text: String): List<List<String>> {
        val firstLine = text.lineSequence().firstOrNull() ?: ""
        val delim = listOf(',', ';', '\t').maxByOrNull { d -> firstLine.count { it == d } } ?: ','
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val sb = StringBuilder()
        var inQ = false
        var i = 0
        fun endCell() { row.add(sb.toString()); sb.setLength(0) }
        fun endRow() { endCell(); if (row.any { it.isNotBlank() }) rows.add(row); row = mutableListOf() }
        while (i < text.length) {
            val c = text[i]
            when {
                inQ && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> inQ = !inQ
                !inQ && c == delim -> endCell()
                !inQ && c == '\n' -> endRow()
                !inQ && c == '\r' -> {}
                else -> sb.append(c)
            }
            i++
        }
        if (sb.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }

    // ---------- XLSX ----------
    private fun xlsx(bytes: ByteArray): List<List<String>> {
        var shared: ByteArray? = null
        val sheets = sortedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                when {
                    e.name == "xl/sharedStrings.xml" -> shared = z.readBytes()
                    e.name.startsWith("xl/worksheets/sheet") && e.name.endsWith(".xml") -> sheets[e.name] = z.readBytes()
                }
            }
        }
        val sheet = sheets["xl/worksheets/sheet1.xml"] ?: sheets.values.firstOrNull()
            ?: throw IllegalArgumentException("ملف Excel غير صالح")
        val strings = shared?.let { sharedStrings(it) } ?: emptyList()
        return sheetRows(sheet, strings)
    }

    private fun sharedStrings(b: ByteArray): List<String> {
        val p = Xml.newPullParser(); p.setInput(ByteArrayInputStream(b), "UTF-8")
        val out = mutableListOf<String>()
        var cur: StringBuilder? = null
        var inT = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> if (p.name == "si") cur = StringBuilder() else if (p.name == "t") inT = true
                XmlPullParser.TEXT -> if (inT) cur?.append(p.text)
                XmlPullParser.END_TAG -> if (p.name == "t") inT = false else if (p.name == "si") { out.add(cur.toString()); cur = null }
            }
            ev = p.next()
        }
        return out
    }

    private fun colIndex(ref: String): Int {
        var n = 0
        for (c in ref) { if (c.isLetter()) n = n * 26 + (c.uppercaseChar() - 'A' + 1) else break }
        return n - 1
    }

    private fun sheetRows(b: ByteArray, strings: List<String>): List<List<String>> {
        val p = Xml.newPullParser(); p.setInput(ByteArrayInputStream(b), "UTF-8")
        val rows = mutableListOf<List<String>>()
        var row = mutableMapOf<Int, String>()
        var col = 0; var type = ""; var inV = false; var inT = false
        val txt = StringBuilder()
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> row = mutableMapOf()
                    "c" -> { type = p.getAttributeValue(null, "t") ?: ""; val r = p.getAttributeValue(null, "r"); if (r != null) col = colIndex(r); txt.setLength(0) }
                    "v" -> inV = true
                    "t" -> inT = true
                }
                XmlPullParser.TEXT -> if (inV || inT) txt.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "v" -> inV = false
                    "t" -> inT = false
                    "c" -> {
                        val raw = txt.toString()
                        row[col] = if (type == "s") strings.getOrElse(raw.trim().toIntOrNull() ?: -1) { "" } else raw
                        col++
                    }
                    "row" -> {
                        val max = row.keys.maxOrNull() ?: -1
                        val list = (0..max).map { row[it] ?: "" }
                        if (list.any { it.isNotBlank() }) rows.add(list)
                    }
                }
            }
            ev = p.next()
        }
        return rows
    }
}
