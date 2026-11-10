package fr.decmike.lecteur.render

import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.util.Locale

/** Convertit une présentation PowerPoint (.pptx) en HTML, une diapositive par bloc. */
class PptxRenderer(private val pkg: ZipPackage) {

    private var cx = 9144000.0
    private var cy = 6858000.0
    private val phCache = HashMap<String, Map<String, DoubleArray>>()
    private val bgCache = HashMap<String, String?>()

    private class Shape(val kind: String) {
        var x: Double? = null
        var y = 0.0
        var w: Double? = null
        var h = 0.0
        var phType: String? = null
        var phIdx: String? = null
        val html = StringBuilder()
        var img: String? = null
        var fill: String? = null
        var scale = 1.0
        var anchor: String? = null
        var counter = 0
    }

    private class Grp {
        var ox = 0.0; var oy = 0.0; var ex = 1.0; var ey = 1.0
        var cox = 0.0; var coy = 0.0; var cex = 1.0; var cey = 1.0
    }

    private class RFmt {
        var sz: Double? = null
        var b = false
        var i = false
        var u = false
        var color: String? = null
    }

    fun render(): String {
        val pres = pkg.mainPart() ?: "ppt/presentation.xml"
        val rels = pkg.rels(pres)
        val ids = ArrayList<String>()
        pkg.parse(pres) { p ->
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "sldSz" -> {
                            p.attr("cx")?.toDoubleOrNull()?.let { if (it > 0) cx = it }
                            p.attr("cy")?.toDoubleOrNull()?.let { if (it > 0) cy = it }
                        }
                        "sldId" -> p.attr("id", "relationships")?.let { ids.add(it) }
                    }
                }
                ev = p.next()
            }
        } ?: throw IOException("Présentation invalide")

        val sb = StringBuilder()
        var n = 0
        for (id in ids) {
            val part = rels[id] ?: continue
            n++
            try {
                renderSlide(n, part, sb)
            } catch (e: Exception) {
                sb.append("<div class=\"slabel\">Diapositive $n</div>")
                sb.append(Html.note("Diapositive illisible : ${e.message ?: e.javaClass.simpleName}"))
            }
        }
        if (n == 0) sb.append("<p class=\"msg\">Aucune diapositive</p>")
        return Html.page(sb, "gray")
    }

    private fun norm(type: String): String = when (type) {
        "ctrTitle", "title" -> "title"
        "body", "obj" -> "body"
        else -> type
    }

    private fun placeholders(part: String?): Map<String, DoubleArray> {
        if (part == null) return emptyMap()
        return phCache.getOrPut(part) {
            val map = HashMap<String, DoubleArray>()
            pkg.parse(part) { p ->
                var inSp = false
                var type: String? = null
                var idx: String? = null
                var xf: DoubleArray? = null
                var inXfrm = false
                var ev = p.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG) {
                        when (p.name) {
                            "sp" -> { inSp = true; type = null; idx = null; xf = null }
                            "ph" -> if (inSp) { type = p.attr("type") ?: "body"; idx = p.attr("idx") }
                            "xfrm" -> if (inSp) inXfrm = true
                            "off" -> if (inXfrm) {
                                val a = xf ?: DoubleArray(4).also { xf = it }
                                a[0] = p.attr("x")?.toDoubleOrNull() ?: 0.0
                                a[1] = p.attr("y")?.toDoubleOrNull() ?: 0.0
                            }
                            "ext" -> if (inXfrm) {
                                val a = xf ?: DoubleArray(4).also { xf = it }
                                a[2] = p.attr("cx")?.toDoubleOrNull() ?: 0.0
                                a[3] = p.attr("cy")?.toDoubleOrNull() ?: 0.0
                            }
                        }
                    } else if (ev == XmlPullParser.END_TAG) {
                        when (p.name) {
                            "xfrm" -> inXfrm = false
                            "sp" -> {
                                val box = xf
                                val t = type
                                if (inSp && box != null && t != null && box[2] > 0) {
                                    idx?.let { map["idx:$it"] = box }
                                    map.putIfAbsent("type:" + norm(t), box)
                                }
                                inSp = false
                            }
                        }
                    }
                    ev = p.next()
                }
            }
            map
        }
    }

    private fun background(part: String?): String? {
        if (part == null) return null
        if (bgCache.containsKey(part)) return bgCache[part]
        var bg: String? = null
        pkg.parse(part) { p ->
            var inBg = false
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    if (p.name == "bg") inBg = true
                    else if (inBg && p.name == "srgbClr" && bg == null) bg = validHex(p.attr("val"))
                    else if (p.name == "spTree") break
                } else if (ev == XmlPullParser.END_TAG && p.name == "bg") {
                    inBg = false
                }
                ev = p.next()
            }
        }
        bgCache[part] = bg
        return bg
    }

    private fun fs(pt: Double): String = String.format(Locale.US, "%.3fvw", pt / (cx / 12700.0) * 96.0)
    private fun pct(v: Double, total: Double): String = String.format(Locale.US, "%.3f%%", v / total * 100.0)

    private fun renderSlide(num: Int, part: String, sb: StringBuilder) {
        val srels = pkg.relsFull(part)
        val rel = srels.associate { it.id to it.target }
        val layout = srels.firstOrNull { it.type.endsWith("/slideLayout") }?.target
        val master = layout?.let { l -> pkg.relsFull(l).firstOrNull { it.type.endsWith("/slideMaster") }?.target }
        val lph = placeholders(layout)
        val mph = placeholders(master)

        val shapes = StringBuilder()
        var bg: String? = null
        val groups = ArrayList<Grp>()
        var cur: Shape? = null
        var xfOwner = 0 // 0 = aucun, 1 = forme, 2 = groupe
        var inGrpSpPr = false
        var inTx = false
        var inSpPr = false
        var inLn = false
        var inEffect = false
        var inSolid = false
        var inBg = false
        var inRPr = false
        var para: StringBuilder? = null
        var pAlign: String? = null
        var buNone = false
        var buChar = false
        var autoNum = false
        var lvl = 0
        var run: RFmt? = null
        var inT = false
        var skip = 0

        fun emitPara(c: Shape, text: String) {
            val isBodyPh = c.phType?.let { norm(it) == "body" } ?: false
            val bullet = !buNone && text.isNotBlank() && (buChar || autoNum || (isBodyPh && c.kind == "sp"))
            val st = StringBuilder()
            when (pAlign) {
                "ctr" -> st.append("text-align:center;")
                "r" -> st.append("text-align:right;")
                "just", "dist" -> st.append("text-align:justify;")
            }
            if (lvl > 0) st.append("padding-left:").append(lvl * 1.2).append("em;")
            c.html.append("<p")
            if (st.isNotEmpty()) c.html.append(" style=\"").append(st).append('"')
            c.html.append('>')
            if (bullet) {
                val prefix = if (autoNum) "${++c.counter}." else "•"
                c.html.append("<span class=\"bu\">").append(prefix).append("</span>")
            }
            c.html.append(if (text.isBlank()) "&nbsp;" else text).append("</p>")
        }

        fun emitShape(c: Shape) {
            var box: DoubleArray? = null
            val x0 = c.x
            val w0 = c.w
            if (x0 != null && w0 != null && w0 > 0) {
                var x = x0; var y = c.y; var w = w0; var h = c.h
                for (g in groups.asReversed()) {
                    val sx = if (g.cex != 0.0) g.ex / g.cex else 1.0
                    val sy = if (g.cey != 0.0) g.ey / g.cey else 1.0
                    x = g.ox + (x - g.cox) * sx
                    y = g.oy + (y - g.coy) * sy
                    w *= sx; h *= sy
                }
                box = doubleArrayOf(x, y, w, h)
            }
            val type = c.phType?.let { norm(it) }
            if (box == null && type != null) {
                box = c.phIdx?.let { lph["idx:$it"] ?: mph["idx:$it"] }
                    ?: lph["type:$type"] ?: mph["type:$type"]
                    ?: if (type == "title") doubleArrayOf(cx * .05, cy * .04, cx * .9, cy * .16)
                    else doubleArrayOf(cx * .05, cy * .22, cx * .9, cy * .7)
            }
            if (c.html.isBlank() && c.img == null && c.fill == null) return
            if (box == null) box = doubleArrayOf(cx * .05, cy * .22, cx * .9, cy * .7)

            val defPt = when (c.phType) {
                "ctrTitle" -> 44.0
                "title" -> 36.0
                "subTitle" -> 24.0
                "body", "obj" -> 20.0
                null -> 18.0
                else -> 12.0
            }
            shapes.append("<div class=\"shape\" style=\"left:").append(pct(box[0], cx))
                .append(";top:").append(pct(box[1], cy))
                .append(";width:").append(pct(box[2], cx))
                .append(";height:").append(pct(box[3], cy))
                .append(";font-size:").append(fs(defPt * c.scale)).append(';')
            c.fill?.let { shapes.append("background:#").append(it).append(';') }
            val img = c.img
            if (img != null && c.html.isBlank()) {
                shapes.append("\"><img src=\"").append(img)
                    .append("\" style=\"width:100%;height:100%;object-fit:fill\"></div>")
                return
            }
            val anchor = c.anchor ?: if (type == "title") "ctr" else "t"
            val justify = when (anchor) { "ctr" -> "center"; "b" -> "flex-end"; else -> "flex-start" }
            shapes.append("display:flex;flex-direction:column;justify-content:").append(justify)
                .append(";padding:").append(pct(45720.0, cx)).append(' ').append(pct(91440.0, cx))
                .append("\">")
            if (img != null) shapes.append("<img src=\"").append(img).append("\">")
            shapes.append(c.html).append("</div>")
        }

        pkg.parse(part) { p ->
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        if (skip > 0) {
                            skip++
                        } else {
                            val c = cur
                            when (p.name) {
                                "Fallback" -> skip = 1
                                "bg" -> inBg = true
                                "grpSp" -> groups.add(Grp())
                                "grpSpPr" -> inGrpSpPr = true
                                "sp", "pic", "graphicFrame", "cxnSp" -> if (c == null) cur = Shape(p.name)
                                "ph" -> if (c != null) { c.phType = p.attr("type") ?: "body"; c.phIdx = p.attr("idx") }
                                "xfrm" -> xfOwner = if (c != null && !inTx) 1 else if (inGrpSpPr) 2 else 0
                                "off" -> {
                                    val x = p.attr("x")?.toDoubleOrNull() ?: 0.0
                                    val y = p.attr("y")?.toDoubleOrNull() ?: 0.0
                                    if (xfOwner == 1 && c != null) { c.x = x; c.y = y }
                                    else if (xfOwner == 2 && groups.isNotEmpty()) groups.last().let { it.ox = x; it.oy = y }
                                }
                                "ext" -> {
                                    val w = p.attr("cx")?.toDoubleOrNull()
                                    val h = p.attr("cy")?.toDoubleOrNull()
                                    if (w != null && h != null) {
                                        if (xfOwner == 1 && c != null) { c.w = w; c.h = h }
                                        else if (xfOwner == 2 && groups.isNotEmpty()) groups.last().let { it.ex = w; it.ey = h }
                                    }
                                }
                                "chOff" -> if (xfOwner == 2 && groups.isNotEmpty()) groups.last().let {
                                    it.cox = p.attr("x")?.toDoubleOrNull() ?: 0.0
                                    it.coy = p.attr("y")?.toDoubleOrNull() ?: 0.0
                                }
                                "chExt" -> if (xfOwner == 2 && groups.isNotEmpty()) groups.last().let {
                                    it.cex = p.attr("cx")?.toDoubleOrNull() ?: 1.0
                                    it.cey = p.attr("cy")?.toDoubleOrNull() ?: 1.0
                                }
                                "spPr" -> inSpPr = true
                                "ln" -> if (inSpPr) inLn = true
                                "effectLst" -> inEffect = true
                                "solidFill" -> inSolid = true
                                "srgbClr" -> {
                                    val v = validHex(p.attr("val"))
                                    val r = run
                                    if (v != null) when {
                                        r != null && inRPr && inSolid -> r.color = v
                                        inBg && inSolid -> bg = v
                                        c != null && inSpPr && inSolid && !inLn && !inEffect && !inTx -> c.fill = v
                                    }
                                }
                                "txBody" -> inTx = true
                                "bodyPr" -> if (c != null) c.anchor = p.attr("anchor")
                                "normAutofit" -> p.attr("fontScale")?.toDoubleOrNull()?.let {
                                    if (c != null && it > 0) c.scale = it / 100000.0
                                }
                                "p" -> if (inTx && c != null) {
                                    para = StringBuilder(); pAlign = null
                                    buNone = false; buChar = false; autoNum = false; lvl = 0
                                }
                                "pPr" -> if (para != null) {
                                    pAlign = p.attr("algn")
                                    lvl = p.attr("lvl")?.toIntOrNull() ?: 0
                                }
                                "buNone" -> if (para != null) buNone = true
                                "buChar" -> if (para != null) buChar = true
                                "buAutoNum" -> if (para != null) autoNum = true
                                "r", "fld" -> if (para != null) run = RFmt()
                                "rPr" -> run?.let { r ->
                                    inRPr = true
                                    r.sz = p.attr("sz")?.toDoubleOrNull()?.div(100.0)
                                    r.b = p.attr("b") == "1" || p.attr("b") == "true"
                                    r.i = p.attr("i") == "1" || p.attr("i") == "true"
                                    r.u = p.attr("u")?.let { it != "none" } ?: false
                                }
                                "t" -> if (run != null) inT = true
                                "br" -> para?.append("<br>")
                                "tbl" -> c?.html?.append("<table class=\"ptbl\">")
                                "tr" -> c?.html?.append("<tr>")
                                "tc" -> if (c != null) {
                                    if (p.attr("hMerge") == "1" || p.attr("vMerge") == "1") {
                                        skip = 1
                                    } else {
                                        c.html.append("<td")
                                        p.attr("gridSpan")?.toIntOrNull()?.let { c.html.append(" colspan=\"").append(it).append('"') }
                                        p.attr("rowSpan")?.toIntOrNull()?.let { c.html.append(" rowspan=\"").append(it).append('"') }
                                        c.html.append('>')
                                    }
                                }
                                "blip" -> if (c != null) {
                                    p.attr("embed", "relationships")?.let { rel[it] }?.let { c.img = pkg.resource(it) }
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (skip > 0) {
                            skip--
                        } else {
                            val c = cur
                            when (p.name) {
                                "bg" -> inBg = false
                                "grpSpPr" -> inGrpSpPr = false
                                "grpSp" -> if (groups.isNotEmpty()) groups.removeAt(groups.size - 1)
                                "xfrm" -> xfOwner = 0
                                "spPr" -> { inSpPr = false; inLn = false }
                                "ln" -> inLn = false
                                "effectLst" -> inEffect = false
                                "solidFill" -> inSolid = false
                                "txBody" -> inTx = false
                                "rPr" -> inRPr = false
                                "t" -> inT = false
                                "r", "fld" -> run = null
                                "p" -> {
                                    val pb = para
                                    if (pb != null && c != null) emitPara(c, pb.toString())
                                    para = null
                                }
                                "tr" -> c?.html?.append("</tr>")
                                "tc" -> c?.html?.append("</td>")
                                "tbl" -> c?.html?.append("</table>")
                                "sp", "pic", "graphicFrame", "cxnSp" -> if (c != null && c.kind == p.name) {
                                    emitShape(c); cur = null
                                }
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val r = run
                        val pb = para
                        if (skip == 0 && inT && r != null && pb != null) {
                            val scale = cur?.scale ?: 1.0
                            val st = StringBuilder()
                            r.sz?.let { st.append("font-size:").append(fs(it * scale)).append(';') }
                            r.color?.let { st.append("color:#").append(it).append(';') }
                            if (r.b) st.append("font-weight:bold;")
                            if (r.i) st.append("font-style:italic;")
                            if (r.u) st.append("text-decoration:underline;")
                            if (st.isEmpty()) pb.append(Html.esc(p.text))
                            else pb.append("<span style=\"").append(st).append("\">").append(Html.esc(p.text)).append("</span>")
                        }
                    }
                }
                ev = p.next()
            }
        }

        val bgColor = bg ?: background(layout) ?: background(master) ?: "FFFFFF"
        val hvw = String.format(Locale.US, "%.3f", 96.0 * cy / cx)
        sb.append("<div class=\"slabel\">Diapositive ").append(num).append("</div>")
        sb.append("<div class=\"slide\" style=\"height:").append(hvw).append("vw;background:#").append(bgColor).append("\">")
        sb.append(shapes).append("</div>")
    }
}
