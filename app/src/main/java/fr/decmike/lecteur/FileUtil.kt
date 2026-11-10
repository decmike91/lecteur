package fr.decmike.lecteur

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException
import java.util.UUID

object FileUtil {

    fun queryName(ctx: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) return c.getString(i)
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    fun safeName(name: String): String {
        var n = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim()
        if (n.isEmpty() || n == "." || n == "..") n = "document"
        if (n.length > 120) {
            val ext = n.substringAfterLast('.', "")
            n = if (ext.isNotEmpty() && ext.length < 10) n.take(110) + "." + ext else n.take(120)
        }
        return n
    }

    fun newDir(ctx: Context, kind: String): File =
        File(ctx.cacheDir, kind + "/" + UUID.randomUUID().toString()).apply { mkdirs() }

    fun copyToCache(ctx: Context, uri: Uri, name: String): File {
        val f = File(newDir(ctx, "open"), safeName(name))
        val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("Impossible de lire le fichier")
        input.use { i -> f.outputStream().use { i.copyTo(it, 64 * 1024) } }
        return f
    }

    /** Supprime les copies temporaires de plus de 6 heures. */
    fun cleanOld(ctx: Context) {
        val limit = System.currentTimeMillis() - 6L * 3600 * 1000
        for (sub in listOf("open", "view", "extract")) {
            File(ctx.cacheDir, sub).listFiles()?.forEach { d ->
                if (d.lastModified() < limit) d.deleteRecursively()
            }
        }
    }

    fun mimeFor(name: String, fallback: String?): String {
        val ext = FileKind.ext(name)
        val m = if (ext.isEmpty()) null else MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        return m ?: fallback?.takeIf { it.contains('/') && it != "*/*" } ?: "application/octet-stream"
    }
}
