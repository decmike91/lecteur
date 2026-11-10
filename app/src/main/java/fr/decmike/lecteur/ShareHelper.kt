package fr.decmike.lecteur

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/** Partage du document : e-mail, SMS/MMS, WhatsApp ou autre application. */
object ShareHelper {

    fun uriFor(activity: Activity, file: File): Uri =
        FileProvider.getUriForFile(activity, activity.packageName + ".fileprovider", file)

    private fun baseIntent(uri: Uri, mime: String, name: String) = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, name)
        putExtra(Intent.EXTRA_TITLE, name)
        clipData = ClipData.newRawUri(name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun show(activity: Activity, file: File, name: String, mime: String) {
        val uri = uriFor(activity, file)
        val options = arrayOf("✉️  E-mail", "💬  SMS / MMS", "🟢  WhatsApp", "📤  Autres applications…")
        MaterialAlertDialogBuilder(activity)
            .setTitle("Partager « $name »")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> email(activity, uri, mime, name)
                    1 -> sms(activity, uri, mime, name)
                    2 -> whatsapp(activity, uri, mime, name)
                    else -> chooser(activity, uri, mime, name)
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun chooser(activity: Activity, uri: Uri, mime: String, name: String) {
        val chooser = Intent.createChooser(baseIntent(uri, mime, name), "Partager via")
        activity.startActivity(chooser)
    }

    private fun email(activity: Activity, uri: Uri, mime: String, name: String) {
        val i = baseIntent(uri, mime, name).apply {
            selector = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
        }
        try {
            activity.startActivity(i)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, "Aucune application e-mail trouvée", Toast.LENGTH_SHORT).show()
            chooser(activity, uri, mime, name)
        }
    }

    private fun sms(activity: Activity, uri: Uri, mime: String, name: String) {
        val pkg = Telephony.Sms.getDefaultSmsPackage(activity)
        val i = baseIntent(uri, mime, name).apply {
            if (pkg != null) setPackage(pkg)
            putExtra("sms_body", name)
        }
        try {
            if (pkg != null) activity.startActivity(i) else chooser(activity, uri, mime, name)
        } catch (e: ActivityNotFoundException) {
            chooser(activity, uri, mime, name)
        }
    }

    private fun whatsapp(activity: Activity, uri: Uri, mime: String, name: String) {
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            try {
                activity.startActivity(baseIntent(uri, mime, name).setPackage(pkg))
                return
            } catch (_: ActivityNotFoundException) {
            }
        }
        Toast.makeText(activity, "WhatsApp n'est pas installé", Toast.LENGTH_SHORT).show()
    }

    fun openWith(activity: Activity, file: File, mime: String) {
        val uri = uriFor(activity, file)
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            activity.startActivity(Intent.createChooser(i, "Ouvrir avec"))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, "Aucune autre application ne peut ouvrir ce fichier", Toast.LENGTH_SHORT).show()
        }
    }
}
