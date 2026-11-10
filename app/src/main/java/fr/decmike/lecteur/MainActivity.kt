package fr.decmike.lecteur

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: EntryAdapter
    private lateinit var empty: TextView
    private var items: List<Recent> = emptyList()

    private val openDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPicked(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))

        findViewById<View>(R.id.btnOpen).setOnClickListener { openDoc.launch(arrayOf("*/*")) }
        empty = findViewById(R.id.empty)
        adapter = EntryAdapter(
            onClick = { pos -> items.getOrNull(pos)?.let { openViewer(Uri.parse(it.uri), it.name) } },
            onLongClick = { pos -> items.getOrNull(pos)?.let { confirmRemove(it) } },
        )
        findViewById<RecyclerView>(R.id.recents).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                FileUtil.cleanOld(applicationContext)
            } catch (_: Exception) {
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        items = Recents.list(this)
        val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        adapter.rows = items.map {
            val icon = if (FileKind.byExtension(it.name) == Kind.ARCHIVE) R.drawable.ic_folder else R.drawable.ic_file
            EntryAdapter.Row(icon, it.name, df.format(Date(it.time)))
        }
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun onPicked(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
        val name = FileUtil.queryName(this, uri) ?: "document"
        Recents.add(this, uri, name)
        openViewer(uri, name)
    }

    private fun openViewer(uri: Uri, name: String) {
        val i = Intent(this, ViewerActivity::class.java)
            .setData(uri)
            .putExtra(ViewerActivity.EXTRA_NAME, name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(i)
    }

    private fun confirmRemove(r: Recent) {
        MaterialAlertDialogBuilder(this)
            .setTitle(r.name)
            .setMessage("Retirer ce fichier de la liste des récents ?\n(Le fichier lui-même n'est pas supprimé.)")
            .setPositiveButton("Retirer") { _, _ ->
                try {
                    contentResolver.releasePersistableUriPermission(Uri.parse(r.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {
                }
                Recents.remove(this, r.uri)
                refresh()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_clear -> {
                MaterialAlertDialogBuilder(this)
                    .setMessage("Effacer la liste des fichiers récents ?")
                    .setPositiveButton("Effacer") { _, _ ->
                        for (p in contentResolver.persistedUriPermissions) {
                            try {
                                contentResolver.releasePersistableUriPermission(p.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            } catch (_: Exception) {
                            }
                        }
                        Recents.clear(this); refresh()
                    }
                    .setNegativeButton("Annuler", null)
                    .show()
                return true
            }
            R.id.action_about -> {
                val version = try {
                    packageManager.getPackageInfo(packageName, 0).versionName
                } catch (_: Exception) {
                    ""
                }
                MaterialAlertDialogBuilder(this)
                    .setTitle("Lecteur $version")
                    .setMessage(R.string.about_text)
                    .setPositiveButton("OK", null)
                    .show()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }
}
