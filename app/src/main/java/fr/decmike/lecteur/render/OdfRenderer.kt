package fr.decmike.lecteur.render

import org.xmlpull.v1.XmlPullParser
import java.io.IOException

/** Convertit un document OpenDocument (.odt, .ods, .odp) en HTML. */
class OdfRenderer(private val pkg: ZipPackage, private val spreadsheet: Boolean, private val presentation: Boolean) {

    private class CellM(val html: String, val colspan: Int, val rowspan: Int, val repeat: Int) {
        val empty get() = html.isBlank()
    }

    private class RowM(val repeat: Int) {
        val cells = ArrayList<CellM>()
    }

    private class TableM(val name: String) {
        val rows = ArrayList<RowM>()
        var row: RowM? = null
    }

    private val textCss = HashMap<String, String>()
    private val paraCss = HashMap<String, String>()

    fun render(): String {
        val out = StringBuilder()
        val sheetNames = ArrayList<String>()
        pkg.parse("content.xml") { p -> parse(p, out, sheetNames) } ?: throw IOException("Document OpenDocument invalide")
        val body = StringBuilder()
        if (spreadsheet && sheetNames.size > 1) {
            body.append("<div class=\"nav\">")
            sheetNames.forEachIndexed { i, n -> body.append("<a href=\"#s").append(i).append("\">").append(Html.esc(n)).append("</a>") }
            body.append("</div>")
        }
        return if (presentation) {
            Html.page(body.append(out), "gray")
        } else {
            Html.page(body.append("<div class=\"doc\">").append(out).append("</div>"))
        }
    }

    private fun parse(p: XmlPullParser, out: StringBuilder, sheetNames: MutableList<String>) {
        val sinks = ArrayList<StringBuilder>().apply { add(out) }
        val tables = ArrayList<TableM>()
        val cellAttrs = ArrayList<IntArray>()
        val openLen = ArrayList<Int>()
        val closeTags = ArrayList<String>()
        var inAuto = false
        var curStyle: String? = null
        var skip = 0
        var pDepth = 0
        var listDepth = 0
        var bulletPending = false
        var page = 0

        fun sink() = sinks[sinks.size - 1]
        fun styleAttr(name: String?, para: Boolean): String {
            if (name == null) return ""
            val css = (if (para) paraCss[name] else null).orEmpty() + textCss[name].orEmpty()
            return if (css.isEmpty()) "" else " style=\"$css\""
        }
        fun startBlock(tag: String, close: String) {
            closeTags.add(close)
            val sb = sink()
            sb.append(tag)
            if (bulletPending && listDepth > 0) {
                sb.append("<span class=\"num\" style=\"margin-left:").append((listDepth - 1) * 1.5).append("em\">•</span> ")
                bulletPending = false
            }
            openLen.add(sb.length)
            pDepth++
        }
        fun endBlock() {
            val close = if (closeTags.isEmpty()) "</div>" else closeTags.removeAt(closeTags.size - 1)
            val sb = sink()
            val start = if (openLen.isEmpty()) -1 else openLen.removeAt(openLen.size - 1)
            if (start == sb.length) sb.append("&nbsp;")
            sb.append(close)
            if (pDepth > 0) pDepth--
        }

        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    if (skip > 0) {
                        skip++
                    } else {
                        val n = p.name
                        if (inAuto) {
                            when (n) {
                                "style" -> curStyle = p.attr("name")
                                "text-properties" -> curStyle?.let { s ->
                                    val css = StringBuilder()
                                    if (p.attr("font-weight") == "bold") css.append("font-weight:bold;")
                                    if (p.attr("font-style") == "italic") css.append("font-style:italic;")
                                    val ul = p.attr("text-underline-style")
                                    if (ul != null && ul != "none") css.append("text-decoration:underline;")
                                    p.attr("color")?.takeIf { it.startsWith("#") }?.let { c ->
                                        validHex(c.drop(1))?.let { css.append("color:#").append(it).append(';') }
                                    }
                                    if (css.isNotEmpty()) textCss[s] = css.toString()
                                }
                                "paragraph-properties" -> curStyle?.let { s ->
                                    when (p.attr("text-align")) {
                                        "center" -> paraCss[s] = "text-align:center;"
                                        "end", "right" -> paraCss[s] = "text-align:right;"
                                        "justify" -> paraCss[s] = "text-align:justify;"
                                    }
                                }
                            }
                        } else when (n) {
                            "automatic-styles" -> inAuto = true
                            "tracked-changes", "notes", "forms", "sequence-decls", "annotation", "note",
                            "covered-table-cell", "table-columns", "table-column", "title", "desc" -> skip = 1
                            "h" -> {
                                val lv = (p.attr("outline-level")?.toIntOrNull() ?: 1).coerceIn(1, 6)
                                startBlock("<h$lv${styleAttr(p.attr("style-name"), true)}>", "</h$lv>")
                            }
                            "p" -> startBlock("<div class=\"p\"${styleAttr(p.attr("style-name"), true)}>", "</div>")
                            "span" -> sink().append("<span").append(styleAttr(p.attr("style-name"), false)).append('>')
                            "s" -> {
                                val c = (p.attr("c")?.toIntOrNull() ?: 1).coerceIn(1, 50)
                                repeat(c) { sink().append("&nbsp;") }
                            }
                            "tab" -> sink().append("&emsp;")
                            "line-break" -> sink().append("<br>")
                            "list" -> listDepth++
                            "list-item" -> bulletPending = true
                            "image" -> {
                                val href = p.attr("href")
                                if (href != null && !href.contains("://")) {
                                    pkg.resource(href.removePrefix("./"))?.let {
                                        sink().append("<img src=\"").append(it).append("\">")
                                    }
                                }
                            }
                            "page" -> {
                                page++
                                val name = p.attr("name")
                                sink().append("<div class=\"card\"><div class=\"slabel\">Diapositive ").append(page)
                                if (name != null && !name.matches(Regex("^(page|Diapositive|Slide)\\s*\\d+$", RegexOption.IGNORE_CASE))) {
                                    sink().append(" – ").append(Html.esc(name))
                                }
                                sink().append("</div>")
                            }
                            "table" -> {
                                val name = p.attr("name") ?: ""
                                tables.add(TableM(name))
                            }
                            "table-row" -> tables.lastOrNull()?.row =
                                RowM((p.attr("number-rows-repeated")?.toIntOrNull() ?: 1).coerceAtLeast(1))
                            "table-cell" -> {
                                cellAttrs.add(intArrayOf(
                                    (p.attr("number-columns-spanned")?.toIntOrNull() ?: 1).coerceAtLeast(1),
                                    (p.attr("number-rows-spanned")?.toIntOrNull() ?: 1).coerceAtLeast(1),
                                    (p.attr("number-columns-repeated")?.toIntOrNull() ?: 1).coerceAtLeast(1),
                                ))
                                sinks.add(StringBuilder())
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (skip > 0) {
                        skip--
                    } else {
                        val n = p.name
                        if (inAuto) {
                            if (n == "automatic-styles") inAuto = false
                            if (n == "style") curStyle = null
                        } else when (n) {
                            "h", "p" -> endBlock()
                            "span" -> sink().append("</span>")
                            "list" -> if (listDepth > 0) listDepth--
                            "list-item" -> bulletPending = false
                            "page" -> sink().append("</div>")
                            "table-cell" -> if (sinks.size > 1) {
                                val html = sinks.removeAt(sinks.size - 1).toString()
                                val a = if (cellAttrs.isEmpty()) intArrayOf(1, 1, 1) else cellAttrs.removeAt(cellAttrs.size - 1)
                                tables.lastOrNull()?.row?.cells?.add(CellM(html, a[0], a[1], a[2]))
                            }
                            "table-row" -> tables.lastOrNull()?.let { t ->
                                t.row?.let { t.rows.add(it) }
                                t.row = null
                            }
                            "table" -> if (tables.isNotEmpty()) {
                                val t = tables.removeAt(tables.size - 1)
                                val sb = sink()
                                if (spreadsheet && tables.isEmpty()) {
                                    sb.append("<h2 class=\"sname\" id=\"s").append(sheetNames.size).append("\">")
                                        .append(Html.esc(t.name)).append("</h2>")
                                    sheetNames.add(t.name)
                                }
                                renderTable(t, sb)
                            }
                        }
                    }
                }
                XmlPullParser.TEXT -> if (skip == 0 && pDepth > 0 && !inAuto) sink().append(Html.esc(p.text))
            }
            ev = p.next()
        }
    }

    private fun renderTable(t: TableM, sb: StringBuilder) {
        val rows = t.rows
        val last = rows.indexOfLast { r -> r.cells.any { !it.empty } }
        if (last < 0) {
            if (spreadsheet) sb.append("<p class=\"msg\">Feuille vide</p>")
            return
        }
        sb.append(if (spreadsheet) "<div class=\"tw\"><table class=\"sheet\">" else "<table>")
        var emitted = 0
        for (i in 0..last) {
            val r = rows[i]
            val lastCell = r.cells.indexOfLast { !it.empty }
            val rowEmpty = lastCell < 0
            val times = if (rowEmpty) minOf(r.repeat, 3) else minOf(r.repeat, 200)
            repeat(times) {
                if (emitted >= 5000) return@repeat
                emitted++
                sb.append("<tr>")
                for (ci in 0..lastCell) {
                    val c = r.cells[ci]
                    val reps = if (c.empty) minOf(c.repeat, 30) else minOf(c.repeat, 100)
                    repeat(reps) {
                        sb.append("<td")
                        if (c.colspan > 1) sb.append(" colspan=\"").append(c.colspan).append('"')
                        if (c.rowspan > 1) sb.append(" rowspan=\"").append(c.rowspan).append('"')
                        sb.append('>').append(c.html).append("</td>")
                    }
                }
                if (rowEmpty) sb.append("<td>&nbsp;</td>")
                sb.append("</tr>")
            }
        }
        sb.append(if (spreadsheet) "</table></div>" else "</table>")
        if (emitted >= 5000) sb.append(Html.note("Affichage limité aux 5000 premières lignes."))
    }
}
