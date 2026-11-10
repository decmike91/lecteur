package fr.decmike.lecteur.render

import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Fichiers texte, CSV et RTF. */
object TextRenderer {

    private const val MAX = 4 * 1024 * 1024

    private fun readUpTo(input: InputStream, max: Int): ByteArray {
        val buf = ByteArray(64 * 1024)
        val out = java.io.ByteArrayOutputStream()
        while (out.size() < max) {
            val n = input.read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    fun decode(b: ByteArray, truncated: Boolean = false): String {
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte())
            return String(b, 3, b.size - 3, Charsets.UTF_8)
        if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte())
            return String(b, 2, b.size - 2, Charsets.UTF_16LE)
        if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte())
            return String(b, 2, b.size - 2, Charsets.UTF_16BE)
        val tries = if (truncated) 0..3 else 0..0
        for (k in tries) {
            try {
                val dec = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                return dec.decode(ByteBuffer.wrap(b, 0, b.size - k)).toString()
            } catch (_: CharacterCodingException) {
            }
        }
        return String(b, Charset.forName("windows-1252"))
    }

    private fun read(file: File): Pair<String, Boolean> {
        val truncated = file.length() > MAX
        val bytes = file.inputStream().use { readUpTo(it, MAX) }
        return decode(bytes, truncated) to truncated
    }

    private fun truncNote(t: Boolean) = if (t) Html.note("Fichier volumineux : seuls les 4 premiers Mo sont affichés.") else ""

    fun text(file: File): String {
        val (t, trunc) = read(file)
        return Html.page(truncNote(trunc) + "<pre class=\"txt\">" + Html.esc(t) + "</pre>")
    }

    fun csv(file: File): String {
        val (t, trunc) = read(file)
        val firstLine = t.lineSequence().firstOrNull() ?: ""
        val delim = listOf(';', ',', '\t', '|').maxByOrNull { d -> firstLine.count { it == d } } ?: ','
        val data = SheetData()
        var row = 0
        var col = 0
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        fun endCell() {
            val v = cell.toString()
            data.put(row, col, SheetCell(v, v.isNotEmpty() && v.replace(',', '.').replace(" ", "").toDoubleOrNull() != null))
            cell.setLength(0); col++
        }
        while (i < t.length && row < SheetData.MAX_ROWS) {
            val c = t[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < t.length && t[i + 1] == '"') { cell.append('"'); i++ } else quoted = false
                } else cell.append(c)
            } else when (c) {
                '"' -> quoted = true
                delim -> endCell()
                '\r' -> {}
                '\n' -> { endCell(); row++; col = 0 }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || col > 0) endCell()
        val sb = StringBuilder(truncNote(trunc))
        SheetHtml.render(data, sb)
        return Html.page(sb)
    }

    fun rtf(file: File): String {
        val bytes = file.readBytes()
        val text = rtfToText(String(bytes, Charsets.ISO_8859_1))
        val sb = StringBuilder()
        for (line in text.split('\n')) {
            sb.append("<div class=\"p\">").append(if (line.isBlank()) "&nbsp;" else Html.esc(line).replace("\t", "&emsp;")).append("</div>")
        }
        return Html.page("<div class=\"doc\">$sb</div>")
    }

    private val SKIP_DEST = setOf(
        "fonttbl", "colortbl", "stylesheet", "info", "pict", "object", "header", "footer", "headerl", "headerr",
        "headerf", "footerl", "footerr", "footerf", "listtable", "listoverridetable", "rsidtbl", "generator",
        "themedata", "colorschememapping", "datastore", "latentstyles", "xmlnstbl", "fldinst", "filetbl",
        "revtbl", "pgdsctbl", "mmathPr", "wgrffmtfilter", "bkmkstart", "bkmkend", "nonshppict", "footnote"
    )

    fun rtfToText(s: String): String {
        val out = StringBuilder()
        val cp = Charset.forName("windows-1252")
        val stack = ArrayList<Boolean>()
        var skip = false
        var uc = 1
        var pendingSkip = 0
        var i = 0
        val len = s.length
        while (i < len) {
            val c = s[i]
            when (c) {
                '{' -> { stack.add(skip); i++ }
                '}' -> { skip = if (stack.isEmpty()) false else stack.removeAt(stack.size - 1); i++ }
                '\\' -> {
                    i++
                    if (i >= len) break
                    val n = s[i]
                    when {
                        n == '\\' || n == '{' || n == '}' -> { if (!skip) out.append(n); i++ }
                        n == '*' -> { skip = true; i++ }
                        n == '\'' -> {
                            if (i + 2 < len) {
                                val hex = s.substring(i + 1, i + 3)
                                if (!skip) {
                                    if (pendingSkip > 0) pendingSkip--
                                    else hex.toIntOrNull(16)?.let { out.append(String(byteArrayOf(it.toByte()), cp)) }
                                }
                            }
                            i += 3
                        }
                        n.isLetter() -> {
                            val st = i
                            while (i < len && s[i].isLetter()) i++
                            val word = s.substring(st, i)
                            var param: Int? = null
                            if (i < len && (s[i] == '-' || s[i].isDigit())) {
                                val ps = i
                                i++
                                while (i < len && s[i].isDigit()) i++
                                param = s.substring(ps, i).toIntOrNull()
                            }
                            if (i < len && s[i] == ' ') i++
                            if (word != "u") pendingSkip = 0
                            if (skip) {
                                // rien
                            } else if (word in SKIP_DEST) {
                                skip = true
                            } else when (word) {
                                "par", "line", "row", "sect", "page" -> out.append('\n')
                                "tab", "cell" -> out.append('\t')
                                "u" -> {
                                    var v = param ?: 0
                                    if (v < 0) v += 65536
                                    out.append(v.toChar())
                                    pendingSkip = uc
                                }
                                "uc" -> uc = param ?: 1
                                "emdash" -> out.append('—')
                                "endash" -> out.append('–')
                                "bullet" -> out.append('•')
                                "lquote" -> out.append('‘')
                                "rquote" -> out.append('’')
                                "ldblquote" -> out.append('“')
                                "rdblquote" -> out.append('”')
                            }
                        }
                        else -> {
                            if (!skip && n == '~') out.append(' ')
                            i++
                        }
                    }
                }
                '\r', '\n' -> i++
                else -> {
                    if (!skip) {
                        if (pendingSkip > 0) pendingSkip-- else out.append(c)
                    }
                    i++
                }
            }
        }
        return out.toString().trim()
    }
}
