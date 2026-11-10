package fr.decmike.lecteur.render

import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/** Convertit un classeur Excel (.xlsx) en HTML (lecture seule). */
class XlsxRenderer(private val pkg: ZipPackage) {

    private val sst = ArrayList<String>()
    private val xfFmt = ArrayList<Int>()
    private val customFmts = HashMap<Int, String>()
    private var date1904 = false

    private data class SheetRef(val name: String, val part: String)

    fun render(): String {
        val wb = pkg.mainPart() ?: "xl/workbook.xml"
        val rels = pkg.relsFull(wb)
        val relMap = rels.associate { it.id to it.target }
        val sheets = ArrayList<SheetRef>()
        pkg.parse(wb) { p ->
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "workbookPr" -> date1904 = p.attr("date1904").let { it == "1" || it == "true" }
                        "sheet" -> {
                            val state = p.attr("state")
                            val target = p.attr("id", "relationships")?.let { relMap[it] }
                            if (target != null && state != "hidden" && state != "veryHidden") {
                                sheets.add(SheetRef(p.attr("name") ?: "Feuille", target))
                            }
                        }
                    }
                }
                ev = p.next()
            }
        } ?: throw IOException("Classeur Excel invalide")

        loadSharedStrings(rels.firstOrNull { it.type.endsWith("/sharedStrings") }?.target ?: "xl/sharedStrings.xml")
        loadStyles(rels.firstOrNull { it.type.endsWith("/styles") }?.target ?: "xl/styles.xml")

        val body = StringBuilder()
        if (sheets.size > 1) {
            body.append("<div class=\"nav\">")
            sheets.forEachIndexed { i, s ->
                body.append("<a href=\"#s").append(i).append("\">").append(Html.esc(s.name)).append("</a>")
            }
            body.append("</div>")
        }
        sheets.forEachIndexed { i, s ->
            body.append("<h2 class=\"sname\" id=\"s").append(i).append("\">").append(Html.esc(s.name)).append("</h2>")
            try {
                SheetHtml.render(parseSheet(s.part), body)
            } catch (e: Exception) {
                body.append(Html.note("Feuille illisible : ${e.message ?: e.javaClass.simpleName}"))
            }
        }
        if (sheets.isEmpty()) body.append("<p class=\"msg\">Aucune feuille</p>")
        return Html.page(body)
    }

    private fun loadSharedStrings(part: String) {
        pkg.parse(part) { p ->
            val sb = StringBuilder()
            var inSi = false
            var inT = false
            var inRph = false
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "si" -> { inSi = true; sb.setLength(0) }
                        "rPh" -> inRph = true
                        "t" -> if (inSi && !inRph) inT = true
                    }
                    XmlPullParser.END_TAG -> when (p.name) {
                        "si" -> { sst.add(sb.toString()); inSi = false }
                        "rPh" -> inRph = false
                        "t" -> inT = false
                    }
                    XmlPullParser.TEXT -> if (inT) sb.append(p.text)
                }
                ev = p.next()
            }
        }
    }

    private fun loadStyles(part: String) {
        pkg.parse(part) { p ->
            var inCellXfs = false
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "numFmt" -> {
                            val id = p.attr("numFmtId")?.toIntOrNull()
                            val code = p.attr("formatCode")
                            if (id != null && code != null) customFmts[id] = code
                        }
                        "cellXfs" -> inCellXfs = true
                        "xf" -> if (inCellXfs) xfFmt.add(p.attr("numFmtId")?.toIntOrNull() ?: 0)
                    }
                } else if (ev == XmlPullParser.END_TAG && p.name == "cellXfs") {
                    inCellXfs = false
                }
                ev = p.next()
            }
        }
    }

    private fun parseSheet(part: String): SheetData {
        val data = SheetData()
        pkg.parse(part) { p ->
            var row = -1
            var col = -1
            var type: String? = null
            var style = 0
            val v = StringBuilder()
            val inl = StringBuilder()
            var inV = false
            var inIs = false
            var inIsT = false
            var inRph = false
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "row" -> {
                            row = p.attr("r")?.toIntOrNull()?.minus(1) ?: (row + 1)
                            col = -1
                        }
                        "c" -> {
                            val ref = p.attr("r")?.let { SheetHtml.parseRef(it) }
                            if (ref != null) { row = ref[0]; col = ref[1] } else col++
                            type = p.attr("t")
                            style = p.attr("s")?.toIntOrNull() ?: 0
                            v.setLength(0); inl.setLength(0)
                        }
                        "v" -> inV = true
                        "is" -> inIs = true
                        "rPh" -> inRph = true
                        "t" -> if (inIs && !inRph) inIsT = true
                        "mergeCell" -> {
                            val ref = p.attr("ref")
                            if (ref != null && ref.contains(':')) {
                                val a = SheetHtml.parseRef(ref.substringBefore(':'))
                                val b = SheetHtml.parseRef(ref.substringAfter(':'))
                                if (a != null && b != null) data.merges.add(intArrayOf(a[0], a[1], b[0], b[1]))
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> when (p.name) {
                        "v" -> inV = false
                        "is" -> inIs = false
                        "rPh" -> inRph = false
                        "t" -> inIsT = false
                        "c" -> data.put(row, col, cellValue(type, style, v.toString(), inl.toString()))
                    }
                    XmlPullParser.TEXT -> {
                        if (inV) v.append(p.text) else if (inIsT) inl.append(p.text)
                    }
                }
                ev = p.next()
            }
        }
        return data
    }

    private fun cellValue(t: String?, s: Int, v: String, inline: String): SheetCell = when (t) {
        "s" -> SheetCell(v.trim().toIntOrNull()?.let { sst.getOrNull(it) } ?: "", false)
        "inlineStr" -> SheetCell(inline, false)
        "b" -> SheetCell(if (v.trim() == "1") "VRAI" else "FAUX", false)
        "str", "e", "d" -> SheetCell(v, false)
        else -> {
            val d = v.trim().toDoubleOrNull()
            if (d == null) SheetCell(v, false) else SheetCell(formatNumber(d, xfFmt.getOrNull(s) ?: 0), true)
        }
    }

    private fun formatNumber(d: Double, fmtId: Int): String {
        val code = customFmts[fmtId]
        val kind = dateKind(fmtId, code)
        if (kind != null && d >= 0 && d < 2958466) return formatDate(d, kind)
        val percent = fmtId == 9 || fmtId == 10 || (code != null && stripFmt(code).contains('%'))
        return if (percent) plain(d * 100.0) + " %" else plain(d)
    }

    private fun stripFmt(code: String): String =
        code.replace(Regex("\"[^\"]*\""), "").replace(Regex("\\\\."), "").replace(Regex("\\[[^\\]]*\\]"), "")

    /** 0 = date, 1 = heure, 2 = date + heure ; null si ce n'est pas une date. */
    private fun dateKind(id: Int, code: String?): Int? {
        when (id) {
            in 14..17 -> return 0
            in 18..21, in 45..47 -> return 1
            22 -> return 2
        }
        if (code == null) return null
        val s = stripFmt(code).lowercase()
        if (s.contains("general")) return null
        val hasDate = s.contains('d') || s.contains('y') || (s.contains('m') && !s.contains('h') && !s.contains('s'))
        val hasTime = s.contains('h') || s.contains('s')
        return when {
            hasDate && hasTime -> 2
            hasDate -> 0
            hasTime -> 1
            else -> null
        }
    }

    private fun formatDate(serial: Double, kind: Int): String {
        val base = if (date1904) LocalDateTime.of(1904, 1, 1, 0, 0) else LocalDateTime.of(1899, 12, 30, 0, 0)
        val dt = base.plusSeconds((serial * 86400.0).roundToLong())
        val pattern = when (kind) {
            0 -> "dd/MM/yyyy"
            1 -> "HH:mm:ss"
            else -> "dd/MM/yyyy HH:mm"
        }
        return dt.format(DateTimeFormatter.ofPattern(pattern))
    }

    companion object {
        fun plain(d: Double): String {
            if (d.isNaN() || d.isInfinite()) return d.toString()
            if (d == floor(d) && abs(d) < 1e15) return d.toLong().toString()
            return BigDecimal(d).round(MathContext(12)).stripTrailingZeros().toPlainString().replace('.', ',')
        }
    }
}
