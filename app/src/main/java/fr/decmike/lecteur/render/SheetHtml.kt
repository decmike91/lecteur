package fr.decmike.lecteur.render

import java.util.TreeMap

data class SheetCell(val text: String, val numeric: Boolean)

class SheetData {
    val rows = TreeMap<Int, TreeMap<Int, SheetCell>>()
    var maxRow = -1
    var maxCol = -1
    val merges = ArrayList<IntArray>() // r1, c1, r2, c2
    var truncated = false

    fun put(r: Int, c: Int, cell: SheetCell) {
        if (r < 0 || c < 0) return
        if (r >= MAX_ROWS || c >= MAX_COLS) {
            truncated = true; return
        }
        if (cell.text.isEmpty()) return
        rows.getOrPut(r) { TreeMap() }[c] = cell
        if (r > maxRow) maxRow = r
        if (c > maxCol) maxCol = c
    }

    companion object {
        const val MAX_ROWS = 5000
        const val MAX_COLS = 150
    }
}

object SheetHtml {

    fun colName(i: Int): String {
        var n = i + 1
        val sb = StringBuilder()
        while (n > 0) {
            val m = (n - 1) % 26
            sb.insert(0, 'A' + m)
            n = (n - 1) / 26
        }
        return sb.toString()
    }

    /** "B12" -> (row 11, col 1) */
    fun parseRef(ref: String): IntArray? {
        var i = 0
        var col = 0
        while (i < ref.length && ref[i].isLetter()) {
            col = col * 26 + (ref[i].uppercaseChar() - 'A' + 1); i++
        }
        val row = ref.substring(i).toIntOrNull() ?: return null
        if (col == 0) return null
        return intArrayOf(row - 1, col - 1)
    }

    private fun key(r: Int, c: Int): Long = (r.toLong() shl 20) or c.toLong()

    fun render(d: SheetData, out: StringBuilder) {
        if (d.maxRow < 0) {
            out.append("<p class=\"msg\">Feuille vide</p>"); return
        }
        val spans = HashMap<Long, IntArray>()
        val covered = HashSet<Long>()
        for (m in d.merges) {
            if (m[0] > d.maxRow || m[1] > d.maxCol) continue
            val r2 = minOf(m[2], d.maxRow)
            val c2 = minOf(m[3], d.maxCol)
            if (r2 == m[0] && c2 == m[1]) continue
            spans[key(m[0], m[1])] = intArrayOf(r2 - m[0] + 1, c2 - m[1] + 1)
            for (r in m[0]..r2) for (c in m[1]..c2) if (r != m[0] || c != m[1]) covered.add(key(r, c))
        }
        out.append("<div class=\"tw\"><table class=\"sheet\"><tr><th></th>")
        for (c in 0..d.maxCol) out.append("<th>").append(colName(c)).append("</th>")
        out.append("</tr>")
        for (r in 0..d.maxRow) {
            val row = d.rows[r]
            out.append("<tr><th class=\"rn\">").append(r + 1).append("</th>")
            for (c in 0..d.maxCol) {
                val k = key(r, c)
                if (k in covered) continue
                val cell = row?.get(c)
                out.append("<td")
                if (cell?.numeric == true) out.append(" class=\"n\"")
                spans[k]?.let {
                    if (it[0] > 1) out.append(" rowspan=\"").append(it[0]).append('"')
                    if (it[1] > 1) out.append(" colspan=\"").append(it[1]).append('"')
                }
                out.append('>')
                if (cell != null) out.append(Html.esc(cell.text).replace("\n", "<br>"))
                out.append("</td>")
            }
            out.append("</tr>")
        }
        out.append("</table></div>")
        if (d.truncated) {
            out.append(Html.note("Affichage limité aux ${SheetData.MAX_ROWS} premières lignes et ${SheetData.MAX_COLS} premières colonnes."))
        }
    }
}
