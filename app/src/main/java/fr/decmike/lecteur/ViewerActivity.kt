package fr.decmike.lecteur

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.text.format.Formatter
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import fr.decmike.lecteur.archive.ArchiveEntry
import fr.decmike.lecteur.archive.ArchiveReader
import fr.decmike.lecteur.render.DocxRenderer
import fr.decmike.lecteur.render.Html
import fr.decmike.lecteur.render.LegacyOfficeRenderer
import fr.decmike.lecteur.render.OdfRenderer
import fr.decmike.lecteur.render.PptxRenderer
import fr.decmike.lecteur.render.TextRenderer
import fr.decmike.lecteur.render.XlsxRenderer
import fr.decmike.lecteur.render.ZipPackage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** Affiche un document en lecture seule, ou le contenu d'une archive. */
class ViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NAME = "fr.decmike.lecteur.NAME"
    }

    private lateinit var web: WebView
    private lateinit var list: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var pathBar: TextView
    private lateinit var viewDir: File

    private var localFile: File? = null
    private var displayName = "document"
    private var mime: String? = null
    private var kind = Kind.UNKNOWN

    private val pdfLock = Any()
    private var pdf: PdfRenderer? = null
    private var pdfFd: ParcelFileDescriptor? = null

    private var archive: ArchiveReader? = null
    private var currentDir = ""
    private var listing: List<Node> = emptyList()
    private lateinit var listAdapter: EntryAdapter

    private data class Node(val name: String, val path: String, val isDir: Boolean, val entry: ArchiveEntry?)

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = goUp()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        web = findViewById(R.id.web)
        list = findViewById(R.id.list)
        progress = findViewById(R.id.progress)
        pathBar = findViewById(R.id.pathBar)
        onBackPressedDispatcher.addCallback(this, backCallback)

        viewDir = FileUtil.newDir(this, "view")
        setupWeb()

        listAdapter = EntryAdapter(onClick = { pos -> listing.getOrNull(pos)?.let { onNodeClick(it) } })
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = listAdapter

        val uri = incomingUri(intent)
        if (uri == null) {
            showHtml(Html.message("Aucun fichier à afficher"))
            progress.visibility = View.GONE
            return
        }
        mime = intent.type ?: try { contentResolver.getType(uri) } catch (_: Exception) { null }
        displayName = intent.getStringExtra(EXTRA_NAME)
            ?: FileUtil.queryName(this, uri)
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "document"
        title = displayName
        lifecycleScope.launch { open(uri) }
    }

    private fun incomingUri(i: Intent): Uri? =
        if (i.action == Intent.ACTION_SEND) IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java)
        else i.data

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWeb() {
        web.settings.apply {
            javaScriptEnabled = false
            allowFileAccess = true
            allowContentAccess = false
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            useWideViewPort = true
            loadWithOverviewMode = false
            blockNetworkLoads = true
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.scheme == "file") return false
                if (u.scheme == "http" || u.scheme == "https" || u.scheme == "mailto" || u.scheme == "tel") {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, u))
                    } catch (_: Exception) {
                    }
                }
                return true
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url
                if (u.scheme != "file") return null
                val path = u.path ?: return null
                val prefix = viewDir.absolutePath + "/pdf/"
                if (path.startsWith(prefix)) {
                    val idx = path.removePrefix(prefix).substringBefore('.').toIntOrNull() ?: return null
                    val bytes = renderPdfPage(idx) ?: return null
                    return WebResourceResponse("image/jpeg", null, ByteArrayInputStream(bytes))
                }
                return null
            }
        }
    }

    private suspend fun open(uri: Uri) {
        progress.visibility = View.VISIBLE
        try {
            val f = withContext(Dispatchers.IO) { FileUtil.copyToCache(this@ViewerActivity, uri, displayName) }
            localFile = f
            kind = withContext(Dispatchers.IO) { FileKind.detect(displayName, mime, f) }
            invalidateOptionsMenu()
            if (kind == Kind.ARCHIVE) {
                archive = withContext(Dispatchers.IO) { ArchiveReader.open(f, displayName) }
                showArchive()
            } else {
                val html = withContext(Dispatchers.IO) { render(kind, f) }
                if (html != null) showHtml(html) else showFile(f)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            showHtml(errorPage(e))
        } finally {
            progress.visibility = View.GONE
        }
    }

    private fun errorPage(e: Throwable): String {
        val msg = e.message ?: e.javaClass.simpleName
        val hint = when {
            msg.contains("password", true) || msg.contains("mot de passe", true) || e is SecurityException ->
                "Le document semble protégé par un mot de passe."
            e is OutOfMemoryError -> "Le document est trop volumineux pour être affiché."
            msg.contains("RAR5", true) || e.javaClass.simpleName.contains("RarV5") ->
                "Les archives RAR 5 ne sont pas prises en charge."
            else -> msg
        }
        return Html.message("Impossible d'afficher ce fichier",
            "$hint\n\nVous pouvez toujours le partager ou l'ouvrir avec une autre application (menu ⋮).")
    }

    /** Produit la page HTML du document, ou null pour afficher le fichier tel quel (HTML). */
    private fun render(kind: Kind, f: File): String? {
        val res = File(viewDir, "res").apply { mkdirs() }
        return when (kind) {
            Kind.PDF -> pdfPage(f)
            Kind.IMAGE -> Html.page("<img class=\"single\" src=\"${Uri.fromFile(f)}\">", "gray")
            Kind.TEXT -> TextRenderer.text(f)
            Kind.CSV -> TextRenderer.csv(f)
            Kind.RTF -> TextRenderer.rtf(f)
            Kind.HTML -> null
            Kind.DOCX -> ZipPackage(f, res).use { DocxRenderer(it).render() }
            Kind.XLSX -> ZipPackage(f, res).use { XlsxRenderer(it).render() }
            Kind.PPTX -> ZipPackage(f, res).use { PptxRenderer(it).render() }
            Kind.ODT -> ZipPackage(f, res).use { OdfRenderer(it, spreadsheet = false, presentation = false).render() }
            Kind.ODS -> ZipPackage(f, res).use { OdfRenderer(it, spreadsheet = true, presentation = false).render() }
            Kind.ODP -> ZipPackage(f, res).use { OdfRenderer(it, spreadsheet = false, presentation = true).render() }
            Kind.DOC -> LegacyOfficeRenderer.doc(f)
            Kind.XLS -> LegacyOfficeRenderer.xls(f)
            Kind.PPT -> LegacyOfficeRenderer.ppt(f, res)
            Kind.ARCHIVE, Kind.UNKNOWN -> Html.message(
                "Format non pris en charge",
                "Ce type de fichier ne peut pas être affiché. Vous pouvez le partager ou l'ouvrir avec une autre application (menu ⋮)."
            )
        }
    }

    // ---------- PDF ----------

    private fun pdfPage(f: File): String {
        val fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        val r = PdfRenderer(fd)
        synchronized(pdfLock) {
            pdfFd = fd
            pdf = r
        }
        val sb = StringBuilder()
        synchronized(pdfLock) {
            for (i in 0 until r.pageCount) {
                val page = r.openPage(i)
                val w = page.width
                val h = page.height
                page.close()
                sb.append("<img class=\"pdfpage\" loading=\"lazy\" width=\"").append(w)
                    .append("\" height=\"").append(h).append("\" src=\"pdf/").append(i).append(".jpg\">")
            }
        }
        if (r.pageCount == 0) sb.append("<p class=\"msg\">Document vide</p>")
        return Html.page(sb, "gray")
    }

    private fun renderPdfPage(index: Int): ByteArray? = synchronized(pdfLock) {
        val r = pdf ?: return null
        if (index < 0 || index >= r.pageCount) return null
        try {
            val page = r.openPage(index)
            val targetW = (resources.displayMetrics.widthPixels * 1.6).toInt().coerceIn(800, 2200)
            var h = (targetW.toLong() * page.height / maxOf(1, page.width)).toInt()
            var w = targetW
            if (h > 4000) { w = (w * 4000L / h).toInt(); h = 4000 }
            val bmp = Bitmap.createBitmap(w, maxOf(1, h), Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
            bmp.recycle()
            out.toByteArray()
        } catch (e: Throwable) {
            null
        }
    }

    // ---------- Affichage ----------

    private fun showHtml(html: String) {
        val index = File(viewDir, "index.html")
        index.writeText(html, Charsets.UTF_8)
        showFile(index)
    }

    private fun showFile(f: File) {
        list.visibility = View.GONE
        pathBar.visibility = View.GONE
        web.visibility = View.VISIBLE
        web.loadUrl(Uri.fromFile(f).toString())
    }

    // ---------- Archives ----------

    private fun showArchive() {
        web.visibility = View.GONE
        list.visibility = View.VISIBLE
        pathBar.visibility = View.VISIBLE
        refreshListing()
    }

    private fun refreshListing() {
        val a = archive ?: return
        val prefix = if (currentDir.isEmpty()) "" else "$currentDir/"
        val dirs = LinkedHashMap<String, Node>()
        val files = ArrayList<Node>()
        for (e in a.entries) {
            val p = e.path.trimEnd('/')
            if (p.isEmpty() || !p.startsWith(prefix)) continue
            val rest = p.removePrefix(prefix)
            if (rest.isEmpty()) continue
            if (rest.contains('/')) {
                val d = rest.substringBefore('/')
                dirs.getOrPut(d) { Node(d, prefix + d, true, null) }
            } else if (e.isDir) {
                dirs.getOrPut(rest) { Node(rest, prefix + rest, true, null) }
            } else {
                files.add(Node(rest, p, false, e))
            }
        }
        listing = dirs.values.sortedBy { it.name.lowercase() } + files.sortedBy { it.name.lowercase() }
        listAdapter.rows = listing.map { n ->
            if (n.isDir) EntryAdapter.Row(R.drawable.ic_folder, n.name, "Dossier")
            else EntryAdapter.Row(
                R.drawable.ic_file, n.name,
                n.entry?.size?.takeIf { it >= 0 }?.let { Formatter.formatShortFileSize(this, it) } ?: ""
            )
        }
        val count = a.entries.count { !it.isDir }
        pathBar.text = if (currentDir.isEmpty()) "$count fichier(s) dans l'archive" else "📁 /$currentDir"
        backCallback.isEnabled = currentDir.isNotEmpty()
        if (listing.isEmpty()) {
            Toast.makeText(this, "Archive vide", Toast.LENGTH_SHORT).show()
        }
    }

    private fun goUp() {
        currentDir = if (currentDir.contains('/')) currentDir.substringBeforeLast('/') else ""
        refreshListing()
    }

    private fun onNodeClick(n: Node) {
        if (n.isDir) {
            currentDir = n.path
            refreshListing()
            return
        }
        val a = archive ?: return
        val entry = n.entry ?: return
        lifecycleScope.launch {
            progress.visibility = View.VISIBLE
            try {
                val dest = withContext(Dispatchers.IO) {
                    val d = File(FileUtil.newDir(this@ViewerActivity, "extract"), FileUtil.safeName(n.name))
                    a.extract(entry, d)
                    d
                }
                val i = Intent(this@ViewerActivity, ViewerActivity::class.java)
                    .setData(ShareHelper.uriFor(this@ViewerActivity, dest))
                    .putExtra(EXTRA_NAME, n.name)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(i)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                MaterialAlertDialogBuilder(this@ViewerActivity)
                    .setTitle("Extraction impossible")
                    .setMessage(e.message ?: e.javaClass.simpleName)
                    .setPositiveButton("OK", null)
                    .show()
            } finally {
                progress.visibility = View.GONE
            }
        }
    }

    // ---------- Menu ----------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.viewer, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val ready = localFile != null
        for (id in listOf(R.id.action_share, R.id.action_open_with, R.id.action_info)) {
            menu.findItem(id)?.isEnabled = ready
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val f = localFile
        when (item.itemId) {
            android.R.id.home -> { finish(); return true }
            R.id.action_share -> { if (f != null) ShareHelper.show(this, f, displayName, shareMime()); return true }
            R.id.action_open_with -> { if (f != null) ShareHelper.openWith(this, f, shareMime()); return true }
            R.id.action_info -> { if (f != null) showInfo(f); return true }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun shareMime(): String = FileUtil.mimeFor(displayName, mime)

    private fun showInfo(f: File) {
        val extra = synchronized(pdfLock) { pdf?.let { "\nPages : ${it.pageCount}" } } ?: ""
        val arch = archive?.let { a -> "\nFichiers : ${a.entries.count { !it.isDir }}" } ?: ""
        MaterialAlertDialogBuilder(this)
            .setTitle("Informations")
            .setMessage(
                "Nom : $displayName\nType : ${kind.label}\nTaille : ${Formatter.formatFileSize(this, f.length())}$extra$arch" +
                    "\n\nLecture seule : le fichier d'origine n'est jamais modifié."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onDestroy() {
        synchronized(pdfLock) {
            try { pdf?.close() } catch (_: Exception) {}
            try { pdfFd?.close() } catch (_: Exception) {}
            pdf = null
            pdfFd = null
        }
        try { archive?.close() } catch (_: Exception) {}
        web.destroy()
        super.onDestroy()
        if (isFinishing) {
            Thread {
                viewDir.deleteRecursively()
                localFile?.parentFile?.deleteRecursively()
            }.start()
        }
    }
}
