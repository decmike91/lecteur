package fr.decmike.lecteur

import java.io.File
import java.util.zip.ZipFile

enum class Kind(val label: String) {
    PDF("Document PDF"), IMAGE("Image"), TEXT("Texte"), CSV("Tableau CSV"), HTML("Page HTML"),
    DOCX("Document Word"), XLSX("Classeur Excel"), PPTX("Présentation PowerPoint"),
    ODT("Texte OpenDocument"), ODS("Classeur OpenDocument"), ODP("Présentation OpenDocument"),
    DOC("Document Word (ancien format)"), XLS("Classeur Excel (ancien format)"), PPT("Présentation PowerPoint (ancien format)"),
    RTF("Texte enrichi RTF"), ARCHIVE("Archive"), UNKNOWN("Fichier")
}

object FileKind {

    private val ARCHIVE_SUFFIXES = listOf(
        ".zip", ".7z", ".rar", ".tar", ".tar.gz", ".tgz", ".gz", ".bz2", ".tbz", ".tbz2", ".tar.bz2",
        ".xz", ".txz", ".tar.xz", ".jar", ".cbz", ".cbr", ".cb7", ".epub"
    )

    private val TEXT_EXT = setOf(
        "txt", "log", "md", "markdown", "json", "xml", "yml", "yaml", "ini", "cfg", "conf", "properties",
        "kt", "java", "py", "js", "ts", "c", "cpp", "h", "hpp", "cs", "sh", "bat", "ps1", "sql", "srt", "vtt",
        "vcf", "ics", "css", "gradle", "toml", "nfo", "tex", "php", "rb", "go", "rs", "swift", "m3u", "gpx", "kml"
    )

    fun ext(name: String) = name.substringAfterLast('.', "").lowercase()

    fun byExtension(name: String): Kind? {
        val n = name.lowercase()
        if (ARCHIVE_SUFFIXES.any { n.endsWith(it) }) return Kind.ARCHIVE
        return when (val e = ext(n)) {
            "pdf" -> Kind.PDF
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "ico", "heic", "heif", "avif" -> Kind.IMAGE
            "csv", "tsv" -> Kind.CSV
            "htm", "html", "xhtml" -> Kind.HTML
            "docx", "docm", "dotx", "dotm" -> Kind.DOCX
            "xlsx", "xlsm", "xltx", "xltm" -> Kind.XLSX
            "pptx", "pptm", "ppsx", "ppsm", "potx", "potm" -> Kind.PPTX
            "odt", "ott" -> Kind.ODT
            "ods", "ots" -> Kind.ODS
            "odp", "otp" -> Kind.ODP
            "doc", "dot" -> Kind.DOC
            "xls", "xlt" -> Kind.XLS
            "ppt", "pps", "pot" -> Kind.PPT
            "rtf" -> Kind.RTF
            else -> if (e in TEXT_EXT) Kind.TEXT else null
        }
    }

    fun byMime(mime: String?): Kind? {
        val m = mime?.lowercase() ?: return null
        return when {
            m == "application/pdf" -> Kind.PDF
            m.startsWith("image/") -> Kind.IMAGE
            m == "text/csv" || m == "text/comma-separated-values" -> Kind.CSV
            m == "text/html" -> Kind.HTML
            m == "text/rtf" || m == "application/rtf" -> Kind.RTF
            m.startsWith("text/") || m == "application/json" || m == "application/xml" -> Kind.TEXT
            m.contains("wordprocessingml") -> Kind.DOCX
            m.contains("spreadsheetml") -> Kind.XLSX
            m.contains("presentationml") -> Kind.PPTX
            m == "application/msword" -> Kind.DOC
            m == "application/vnd.ms-excel" -> Kind.XLS
            m == "application/vnd.ms-powerpoint" -> Kind.PPT
            m == "application/vnd.oasis.opendocument.text" -> Kind.ODT
            m == "application/vnd.oasis.opendocument.spreadsheet" -> Kind.ODS
            m == "application/vnd.oasis.opendocument.presentation" -> Kind.ODP
            m.contains("zip") || m.contains("rar") || m.contains("7z") || m.contains("tar") ||
                m.contains("gzip") || m.contains("bzip") || m.contains("x-xz") -> Kind.ARCHIVE
            else -> null
        }
    }

    /** Reconnaît le type d'après le contenu du fichier (quand l'extension manque ou est inconnue). */
    fun sniff(file: File): Kind {
        val head = ByteArray(8)
        val n = file.inputStream().use { it.read(head) }
        fun magic(vararg b: Int) = n >= b.size && b.indices.all { head[it] == b[it].toByte() }
        return when {
            magic(0x25, 0x50, 0x44, 0x46) -> Kind.PDF
            magic(0x50, 0x4B, 0x03, 0x04) -> sniffZip(file)
            magic(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1) -> sniffOle(file)
            magic(0x7B, 0x5C, 0x72, 0x74, 0x66) -> Kind.RTF
            magic(0x89, 0x50, 0x4E, 0x47) || magic(0xFF, 0xD8, 0xFF) || magic(0x47, 0x49, 0x46) -> Kind.IMAGE
            magic(0x37, 0x7A, 0xBC, 0xAF) || magic(0x52, 0x61, 0x72, 0x21) || magic(0x1F, 0x8B) ||
                magic(0x42, 0x5A, 0x68) || magic(0xFD, 0x37, 0x7A, 0x58) -> Kind.ARCHIVE
            looksLikeText(file) -> Kind.TEXT
            else -> Kind.UNKNOWN
        }
    }

    private fun sniffZip(file: File): Kind = try {
        ZipFile(file).use { z ->
            when {
                z.getEntry("word/document.xml") != null -> Kind.DOCX
                z.getEntry("xl/workbook.xml") != null -> Kind.XLSX
                z.getEntry("ppt/presentation.xml") != null -> Kind.PPTX
                z.getEntry("mimetype") != null -> {
                    val mt = z.getInputStream(z.getEntry("mimetype")).use { it.readBytes().toString(Charsets.US_ASCII) }
                    byMime(mt.trim()) ?: Kind.ARCHIVE
                }
                else -> Kind.ARCHIVE
            }
        }
    } catch (_: Exception) {
        Kind.ARCHIVE
    }

    private fun sniffOle(file: File): Kind {
        // Recherche simple des noms de flux OLE (encodés en UTF-16LE) dans le début du fichier.
        val bytes = file.inputStream().use { s ->
            val b = ByteArray(minOf(file.length(), 2L * 1024 * 1024).toInt())
            var off = 0
            while (off < b.size) {
                val r = s.read(b, off, b.size - off)
                if (r < 0) break
                off += r
            }
            b
        }
        val s = String(bytes, Charsets.UTF_16LE) + String(bytes.copyOfRange(1, bytes.size), Charsets.UTF_16LE)
        return when {
            s.contains("WordDocument") -> Kind.DOC
            s.contains("Workbook") || s.contains("Book") -> Kind.XLS
            s.contains("PowerPoint Document") -> Kind.PPT
            else -> Kind.UNKNOWN
        }
    }

    private fun looksLikeText(file: File): Boolean {
        val b = ByteArray(4096)
        val n = file.inputStream().use { it.read(b) }
        if (n <= 0) return true
        var bad = 0
        for (i in 0 until n) {
            val c = b[i].toInt() and 0xFF
            if (c == 0) return false
            if (c < 9 || (c in 14..31)) bad++
        }
        return bad * 20 < n
    }

    fun detect(name: String, mime: String?, file: File): Kind {
        val k = byExtension(name) ?: byMime(mime)
        if (k != null && k != Kind.TEXT) return k
        val s = sniff(file)
        return if (s != Kind.UNKNOWN) s else k ?: Kind.UNKNOWN
    }
}
