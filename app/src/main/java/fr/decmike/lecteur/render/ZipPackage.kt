package fr.decmike.lecteur.render

import android.net.Uri
import org.xmlpull.v1.XmlPullParser
import java.io.Closeable
import java.io.File
import java.util.zip.ZipFile

/** Accès en lecture à un document « zippé » (OOXML, OpenDocument). */
class ZipPackage(file: File, private val resDir: File) : Closeable {

    private val zip = ZipFile(file)

    data class Rel(val id: String, val type: String, val target: String)

    fun exists(name: String) = zip.getEntry(name) != null

    fun readText(name: String): String? {
        val e = zip.getEntry(name) ?: return null
        return zip.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    fun <T> parse(name: String, block: (XmlPullParser) -> T): T? {
        val e = zip.getEntry(name) ?: return null
        return zip.getInputStream(e).use { block(XmlUtil.parser(it)) }
    }

    fun relsFull(part: String): List<Rel> {
        val dir = part.substringBeforeLast('/', "")
        val fileName = part.substringAfterLast('/')
        val relsPath = (if (dir.isEmpty()) "" else "$dir/") + "_rels/$fileName.rels"
        return parse(relsPath) { p ->
            val list = ArrayList<Rel>()
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && p.name == "Relationship") {
                    val id = p.attr("Id")
                    val target = p.attr("Target")
                    val type = p.attr("Type") ?: ""
                    if (id != null && target != null && p.attr("TargetMode") != "External") {
                        list.add(Rel(id, type, resolve(dir, target)))
                    }
                }
                ev = p.next()
            }
            list
        } ?: emptyList()
    }

    fun rels(part: String): Map<String, String> = relsFull(part).associate { it.id to it.target }

    fun mainPart(): String? =
        relsFull("").firstOrNull { it.type.endsWith("/officeDocument") }?.target?.takeIf { exists(it) }

    private fun resolve(baseDir: String, target: String): String {
        val t = Uri.decode(target).replace('\\', '/')
        val raw = when {
            t.startsWith("/") -> t.substring(1)
            baseDir.isEmpty() -> t
            else -> "$baseDir/$t"
        }
        return normalize(raw)
    }

    /**
     * Extrait une image du paquet vers le dossier de ressources et renvoie son URL relative,
     * ou null si le format n'est pas affichable.
     */
    fun resource(part: String): String? {
        val path = normalize(part)
        val ext = path.substringAfterLast('.', "").lowercase()
        if (ext !in WEB_IMAGES) return null
        val e = zip.getEntry(path) ?: return null
        val out = File(resDir, path)
        if (!out.canonicalPath.startsWith(resDir.canonicalPath + File.separator)) return null
        if (!out.exists()) {
            out.parentFile?.mkdirs()
            zip.getInputStream(e).use { i -> out.outputStream().use { i.copyTo(it) } }
        }
        return "res/" + path.split('/').joinToString("/") { Uri.encode(it) }
    }

    override fun close() = zip.close()

    companion object {
        val WEB_IMAGES = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp", "svg")

        fun normalize(path: String): String {
            val out = ArrayList<String>()
            for (seg in path.split('/')) {
                when (seg) {
                    "", "." -> {}
                    ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                    else -> out.add(seg)
                }
            }
            return out.joinToString("/")
        }
    }
}
