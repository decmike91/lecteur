package fr.decmike.lecteur.render

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

object XmlUtil {
    fun parser(input: InputStream): XmlPullParser {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        p.setInput(input, null)
        return p
    }
}

/** Valeur d'un attribut par nom local (et éventuellement une partie de l'espace de noms). */
fun XmlPullParser.attr(local: String, nsPart: String? = null): String? {
    for (i in 0 until attributeCount) {
        if (getAttributeName(i) == local) {
            if (nsPart == null || (getAttributeNamespace(i) ?: "").contains(nsPart)) {
                return getAttributeValue(i)
            }
        }
    }
    return null
}

private val HEX6 = Regex("^[0-9A-Fa-f]{6}$")
fun validHex(v: String?): String? = if (v != null && HEX6.matches(v)) v else null
