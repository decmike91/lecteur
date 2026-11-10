package fr.decmike.lecteur.render

import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.util.Locale

/** Convertit un document Word (.docx) en HTML (lecture seule). */
class DocxRenderer(private val pkg: ZipPackage) {

    private class Para {
        val sb = StringBuilder()
        var style: String? = null
        var align: String? = null
        var numId: String? = null
        var ilvl = 0
    }

    private class RunFmt {
        var b = false
        var i = false
        var u = false
        var s = false
        var color: String? = null
        var sz: Double? = null
        var vert: String? = null
        var highlight: String? = null

        fun open(sb: StringBuilder) {
            val st = StringBuilder()
            color?.let { st.append("color:#").append(it).append(';') }
            sz?.let { st.append("font-size:").append(String.format(Locale.US, "%.1f", it * 1.4)).append("px;") }
            highlight?.let { st.append("background:").append(it).append(';') }
            sb.append("<span")
            if (st.isNotEmpty()) sb.append(" style=\"").append(st).append('"')
            sb.append('>')
            if (b) sb.append("<b>")
            if (i) sb.append("<i>")
            if (u) sb.append("<u>")
            if (s) sb.append("<s>")
            if (vert == "superscript") sb.append("<sup>") else if (vert == "subscript") sb.append("<sub>")
        }

        fun close(sb: StringBuilder) {
            if (vert == "superscript") sb.append("</sup>") else if (vert == "subscript") sb.append("</sub>")
            if (s) sb.append("</s>")
            if (u) sb.append("</u>")
            if (i) sb.append("</i>")
            if (b) sb.append("</b>")
            sb.append("</span>")
        }
    }

    private data class LvlDef(val fmt: String, val text: String, val start: Int)

    private val headingLevels = HashMap<String, Int>()
    private val numDefs = HashMap<String, Map<Int, LvlDef>>()
    private val counters = HashMap<String, IntArray>()

    fun render(): String {
        loadStyles()
        loadNumbering()
        val docPart = pkg.mainPart() ?: "word/document.xml"
        val rels = pkg.rels(docPart)
        val body = StringBuilder()
        pkg.parse(docPart) { p -> parseBody(p, rels, body) }
            ?: throw IOException("Document Word invalide")
        return Html.page("<div class=\"doc\">$body</div>")
    }

    // ---------- styles & numérotation ----------

    private fun loadStyles() {
        pkg.parse("word/styles.xml") { p ->
            var cur: String? = null
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "style" -> cur = p.attr("styleId")
                        "name" -> if (cur != null) {
                            val n = (p.attr("val") ?: "").lowercase(Locale.ROOT).trim()
                            val m = Regex("^heading\\s*(\\d)$").find(n)
                            when {
                                m != null -> headingLevels[cur] = m.groupValues[1].toInt().coerceIn(1, 6)
                                n == "title" -> headingLevels[cur] = 1
                                n == "subtitle" -> headingLevels[cur] = 2
                            }
                        }
                        "outlineLvl" -> if (cur != null && !headingLevels.containsKey(cur)) {
                            val v = p.attr("val")?.toIntOrNull()
                            if (v != null && v in 0..5) headingLevels[cur] = v + 1
                        }
                    }
                } else if (ev == XmlPullParser.END_TAG && p.name == "style") {
                    cur = null
                }
                ev = p.next()
            }
        }
    }

    private fun loadNumbering() {
        val abstract = HashMap<String, HashMap<Int, LvlDef>>()
        val numToAbs = HashMap<String, String>()
        pkg.parse("word/numbering.xml") { p ->
            var curAbs: String? = null
            var curNum: String? = null
            var lvl = -1
            var fmt = "decimal"
            var text = "%1."
            var start = 1
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "abstractNum" -> curAbs = p.attr("abstractNumId")
                        "lvl" -> if (curAbs != null) {
                            lvl = p.attr("ilvl")?.toIntOrNull() ?: 0
                            fmt = "decimal"; text = "%1."; start = 1
                        }
                        "start" -> if (lvl >= 0) start = p.attr("val")?.toIntOrNull() ?: 1
                        "numFmt" -> if (lvl >= 0) fmt = p.attr("val") ?: "decimal"
                        "lvlText" -> if (lvl >= 0) text = p.attr("val") ?: ""
                        "num" -> curNum = p.attr("numId")
                        "abstractNumId" -> if (curNum != null) p.attr("val")?.let { numToAbs[curNum!!] = it }
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    when (p.name) {
                        "lvl" -> if (curAbs != null && lvl >= 0) {
                            abstract.getOrPut(curAbs!!) { HashMap() }[lvl] = LvlDef(fmt, text, start)
                            lvl = -1
                        }
                        "abstractNum" -> curAbs = null
                        "num" -> curNum = null
                    }
                }
                ev = p.next()
            }
        }
        for ((num, abs) in numToAbs) abstract[abs]?.let { numDefs[num] = it }
    }

    private fun label(numId: String, ilvl: Int): String {
        val defs = numDefs[numId] ?: return "•"
        val def = defs[ilvl] ?: return "•"
        if (def.fmt == "none") return ""
        if (def.fmt == "bullet") return when (ilvl % 3) { 0 -> "•"; 1 -> "◦"; else -> "▪" }
        val arr = counters.getOrPut(numId) { IntArray(9) { l -> (defs[l]?.start ?: 1) - 1 } }
        val lv = ilvl.coerceIn(0, 8)
        arr[lv]++
        for (l in lv + 1 until 9) arr[l] = (defs[l]?.start ?: 1) - 1
        return Regex("%([1-9])").replace(def.text) { m ->
            val l = m.groupValues[1].toInt() - 1
            val v = maxOf(arr[l], defs[l]?.start ?: 1)
            formatNumber(v, defs[l]?.fmt ?: "decimal")
        }
    }

    private fun formatNumber(n: Int, fmt: String): String = when (fmt) {
        "lowerLetter" -> letters(n).lowercase(Locale.ROOT)
        "upperLetter" -> letters(n)
        "lowerRoman" -> roman(n).lowercase(Locale.ROOT)
        "upperRoman" -> roman(n)
        "decimalZero" -> String.format(Locale.ROOT, "%02d", n)
        else -> n.toString()
    }

    private fun letters(n: Int): String {
        if (n <= 0) return n.toString()
        val c = 'A' + (n - 1) % 26
        return c.toString().repeat((n - 1) / 26 + 1)
    }

    private fun roman(num: Int): String {
        if (num <= 0 || num >= 4000) return num.toString()
        val v = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val s = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        var n = num
        val sb = StringBuilder()
        for (i in v.indices) while (n >= v[i]) { sb.append(s[i]); n -= v[i] }
        return sb.toString()
    }

    // ---------- corps du document ----------

    private fun on(p: XmlPullParser): Boolean {
        val v = p.attr("val") ?: return true
        return v != "0" && v != "false" && v != "none"
    }

    private fun parseBody(p: XmlPullParser, rels: Map<String, String>, out: StringBuilder) {
        val paras = ArrayList<Para>()
        var run = RunFmt()
        var inRun = false
        var inRPr = false
        var inPPr = false
        var inT = false
        var inTcPr = false
        var skip = 0
        var pendingTd = false
        val tdAttrs = StringBuilder()
        var extentPx = 0

        fun sink(): StringBuilder = if (paras.isEmpty()) out else paras[paras.size - 1].sb
        fun flushTd() {
            if (pendingTd) {
                sink().append("<td").append(tdAttrs).append('>')
                pendingTd = false
            }
        }
        fun image(target: String?) {
            if (target == null) return
            val src = pkg.resource(target)
            if (src != null) {
                val w = if (extentPx > 0) " style=\"width:${extentPx}px\"" else ""
                sink().append("<img src=\"").append(src).append("\"").append(w).append('>')
            }
            extentPx = 0
        }

        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    if (skip > 0) {
                        skip++
                    } else {
                        val n = p.name
                        val w = (p.namespace ?: "").contains("wordprocessingml")
                        val para = paras.lastOrNull()
                        when {
                            n == "Fallback" -> skip = 1
                            w && (n == "moveFrom" || n == "del") -> skip = 1
                            w && n == "p" -> { flushTd(); paras.add(Para()) }
                            w && n == "pPr" -> inPPr = true
                            w && n == "pStyle" && inPPr && !inRPr -> para?.style = p.attr("val")
                            w && n == "jc" && inPPr && !inTcPr -> para?.align = p.attr("val")
                            w && n == "numId" && inPPr -> para?.numId = p.attr("val")
                            w && n == "ilvl" && inPPr -> para?.ilvl = p.attr("val")?.toIntOrNull() ?: 0
                            w && n == "r" -> { run = RunFmt(); inRun = true }
                            w && n == "rPr" -> inRPr = true
                            w && inRun && inRPr && !inPPr -> when (n) {
                                "b" -> run.b = on(p)
                                "i" -> run.i = on(p)
                                "u" -> run.u = on(p)
                                "strike", "dstrike" -> run.s = on(p)
                                "color" -> run.color = validHex(p.attr("val"))
                                "sz" -> run.sz = p.attr("val")?.toDoubleOrNull()?.div(2.0)
                                "vertAlign" -> run.vert = p.attr("val")
                                "highlight" -> run.highlight = p.attr("val")
                                    ?.takeIf { it.matches(Regex("^[a-zA-Z]+$")) && it != "none" }
                            }
                            w && n == "t" && inRun -> inT = true
                            w && n == "tab" && inRun && !inPPr -> sink().append("&emsp;")
                            w && n == "br" && inRun -> {
                                if (p.attr("type") == "page") sink().append("<hr>") else sink().append("<br>")
                            }
                            w && n == "cr" && inRun -> sink().append("<br>")
                            w && n == "noBreakHyphen" -> sink().append("-")
                            w && n == "tbl" -> { flushTd(); sink().append("<table>") }
                            w && n == "tr" -> sink().append("<tr>")
                            w && n == "tc" -> { pendingTd = true; tdAttrs.setLength(0) }
                            w && n == "tcPr" -> inTcPr = true
                            w && n == "gridSpan" && pendingTd -> {
                                p.attr("val")?.toIntOrNull()?.let { tdAttrs.append(" colspan=\"").append(it).append('"') }
                            }
                            w && n == "shd" && pendingTd && inTcPr -> {
                                validHex(p.attr("fill"))?.let { tdAttrs.append(" style=\"background:#").append(it).append('"') }
                            }
                            n == "extent" -> {
                                val cx = p.attr("cx")?.toLongOrNull()
                                if (cx != null) extentPx = (cx / 9525L).toInt()
                            }
                            n == "blip" -> image(p.attr("embed", "relationships")?.let { rels[it] })
                            n == "imagedata" -> image(p.attr("id", "relationships")?.let { rels[it] })
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (skip > 0) {
                        skip--
                    } else {
                        val n = p.name
                        val w = (p.namespace ?: "").contains("wordprocessingml")
                        if (w) when (n) {
                            "t" -> inT = false
                            "rPr" -> inRPr = false
                            "pPr" -> inPPr = false
                            "r" -> inRun = false
                            "tcPr" -> inTcPr = false
                            "p" -> if (paras.isNotEmpty()) {
                                val para = paras.removeAt(paras.size - 1)
                                sink().append(buildPara(para))
                            }
                            "tbl" -> sink().append("</table>")
                            "tr" -> sink().append("</tr>")
                            "tc" -> { flushTd(); sink().append("</td>") }
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (skip == 0 && inT) {
                        val sb = sink()
                        run.open(sb)
                        sb.append(Html.esc(p.text))
                        run.close(sb)
                    }
                }
            }
            ev = p.next()
        }
    }

    private fun buildPara(para: Para): String {
        val level = para.style?.let { headingLevels[it] }
        val content = para.sb.toString()
        val blank = content.isBlank()
        val label = if (para.numId != null && para.numId != "0" && !blank) label(para.numId!!, para.ilvl) else ""
        val st = StringBuilder()
        when (para.align) {
            "center" -> st.append("text-align:center;")
            "right", "end" -> st.append("text-align:right;")
            "both", "distribute" -> st.append("text-align:justify;")
        }
        if (label.isNotEmpty()) st.append("padding-left:").append(para.ilvl * 1.5).append("em;")
        val style = if (st.isEmpty()) "" else " style=\"$st\""
        val lab = if (label.isEmpty()) "" else "<span class=\"num\">${Html.esc(label)}</span> "
        return if (level != null) {
            if (blank) "" else "<h$level$style>$lab$content</h$level>"
        } else {
            "<div class=\"p\"$style>$lab${if (blank) "&nbsp;" else content}</div>"
        }
    }
}
