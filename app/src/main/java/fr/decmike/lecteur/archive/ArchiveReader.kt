package fr.decmike.lecteur.archive

import com.github.junrar.Archive
import com.github.junrar.rarfile.FileHeader
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.UnicodePathExtraField
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

data class ArchiveEntry(val path: String, val isDir: Boolean, val size: Long, val index: Int)

/** Lecture seule d'une archive : liste des fichiers et extraction d'un fichier à la demande. */
interface ArchiveReader : Closeable {
    val entries: List<ArchiveEntry>
    fun extract(entry: ArchiveEntry, dest: File)

    companion object {
        fun open(file: File, name: String): ArchiveReader {
            val head = ByteArray(8)
            val n = FileInputStream(file).use { it.read(head) }
            fun magic(vararg b: Int) = n >= b.size && b.indices.all { head[it] == b[it].toByte() }
            val lower = name.lowercase()
            val gz = { GzipCompressorInputStream(BufferedInputStream(FileInputStream(file)), true) as InputStream }
            val bz = { BZip2CompressorInputStream(BufferedInputStream(FileInputStream(file)), true) as InputStream }
            val xz = { XZCompressorInputStream(BufferedInputStream(FileInputStream(file)), true) as InputStream }
            val plain = { BufferedInputStream(FileInputStream(file)) as InputStream }
            return when {
                magic(0x50, 0x4B, 0x03, 0x04) || magic(0x50, 0x4B, 0x05, 0x06) -> ZipArchive(file)
                magic(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> SevenZArchive(file)
                magic(0x52, 0x61, 0x72, 0x21) -> RarArchive(file)
                magic(0x1F, 0x8B) -> if (isTar(gz)) TarArchive(gz) else SingleArchive(stripExt(name), gz)
                magic(0x42, 0x5A, 0x68) -> if (isTar(bz)) TarArchive(bz) else SingleArchive(stripExt(name), bz)
                magic(0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00) -> if (isTar(xz)) TarArchive(xz) else SingleArchive(stripExt(name), xz)
                isTar(plain) -> TarArchive(plain)
                lower.endsWith(".zip") || lower.endsWith(".jar") || lower.endsWith(".cbz") -> ZipArchive(file)
                else -> throw IOException("Format d'archive non reconnu")
            }
        }

        private fun stripExt(name: String): String {
            val base = name.substringBeforeLast('.', name)
            return if (base.isEmpty()) "fichier" else base
        }

        private fun isTar(opener: () -> InputStream): Boolean = try {
            opener().use { s ->
                val buf = ByteArray(512)
                var n = 0
                while (n < 512) {
                    val r = s.read(buf, n, 512 - n)
                    if (r < 0) break
                    n += r
                }
                n >= 512 && TarArchiveInputStream.matches(buf, n)
            }
        } catch (_: Exception) {
            false
        }

        fun cleanPath(p: String): String {
            var s = p.replace('\\', '/')
            while (s.startsWith("/")) s = s.substring(1)
            while (s.startsWith("./")) s = s.substring(2)
            return s
        }

        fun copy(input: InputStream, dest: File) {
            dest.parentFile?.mkdirs()
            dest.outputStream().use { input.copyTo(it, 64 * 1024) }
        }
    }
}

private class ZipArchive(file: File) : ArchiveReader {
    private val zf: ZipFile = ZipFile.builder().setFile(file).setCharset(StandardCharsets.UTF_8).get()
    private val raw = ArrayList<ZipArchiveEntry>()
    override val entries: List<ArchiveEntry>

    init {
        val list = ArrayList<ArchiveEntry>()
        val en = zf.entries
        while (en.hasMoreElements()) {
            val e = en.nextElement()
            list.add(ArchiveEntry(ArchiveReader.cleanPath(decodeName(e)), e.isDirectory, e.size, raw.size))
            raw.add(e)
        }
        entries = list
    }

    private fun decodeName(e: ZipArchiveEntry): String {
        if (e.generalPurposeBit.usesUTF8ForNames() || e.getExtraField(UnicodePathExtraField.UPATH_ID) != null) return e.name
        val bytes = e.rawName ?: return e.name
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
        }
        for (cs in listOf("IBM850", "IBM437", "windows-1252")) {
            try {
                return String(bytes, Charset.forName(cs))
            } catch (_: Exception) {
            }
        }
        return String(bytes, StandardCharsets.ISO_8859_1)
    }

    override fun extract(entry: ArchiveEntry, dest: File) {
        val e = raw[entry.index]
        if (!zf.canReadEntryData(e)) {
            throw IOException("Ce fichier est protégé par un mot de passe ou utilise une compression non prise en charge.")
        }
        zf.getInputStream(e).use { ArchiveReader.copy(it, dest) }
    }

    override fun close() = zf.close()
}

private class SevenZArchive(file: File) : ArchiveReader {
    private val sz: SevenZFile = SevenZFile.builder().setFile(file).get()
    private val raw: List<SevenZArchiveEntry> = sz.entries.toList()
    override val entries: List<ArchiveEntry> = raw.mapIndexed { i, e ->
        ArchiveEntry(ArchiveReader.cleanPath(e.name ?: "fichier$i"), e.isDirectory, if (e.hasStream()) e.size else 0L, i)
    }

    @Synchronized
    override fun extract(entry: ArchiveEntry, dest: File) {
        sz.getInputStream(raw[entry.index]).use { ArchiveReader.copy(it, dest) }
    }

    override fun close() = sz.close()
}

private class RarArchive(file: File) : ArchiveReader {
    private val arc = Archive(file)
    private val raw: List<FileHeader> = arc.fileHeaders
    override val entries: List<ArchiveEntry> = raw.mapIndexed { i, h ->
        ArchiveEntry(ArchiveReader.cleanPath(h.fileName ?: "fichier$i"), h.isDirectory, h.fullUnpackSize, i)
    }

    @Synchronized
    override fun extract(entry: ArchiveEntry, dest: File) {
        val h = raw[entry.index]
        if (h.isEncrypted) throw IOException("Ce fichier est protégé par un mot de passe.")
        dest.parentFile?.mkdirs()
        dest.outputStream().use { arc.extractFile(h, it) }
    }

    override fun close() = arc.close()
}

private class TarArchive(private val opener: () -> InputStream) : ArchiveReader {
    override val entries: List<ArchiveEntry>

    init {
        val list = ArrayList<ArchiveEntry>()
        TarArchiveInputStream(opener()).use { t ->
            var e = t.nextEntry
            while (e != null) {
                list.add(ArchiveEntry(ArchiveReader.cleanPath(e.name), e.isDirectory, e.size, list.size))
                e = t.nextEntry
            }
        }
        entries = list
    }

    override fun extract(entry: ArchiveEntry, dest: File) {
        TarArchiveInputStream(opener()).use { t ->
            var i = 0
            var e = t.nextEntry
            while (e != null) {
                if (i == entry.index) {
                    ArchiveReader.copy(t, dest); return
                }
                i++
                e = t.nextEntry
            }
        }
        throw IOException("Fichier introuvable dans l'archive")
    }

    override fun close() {}
}

private class SingleArchive(name: String, private val opener: () -> InputStream) : ArchiveReader {
    override val entries = listOf(ArchiveEntry(name, false, -1L, 0))
    override fun extract(entry: ArchiveEntry, dest: File) {
        opener().use { ArchiveReader.copy(it, dest) }
    }

    override fun close() {}
}
