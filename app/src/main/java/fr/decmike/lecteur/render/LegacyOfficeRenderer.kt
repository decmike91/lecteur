package fr.decmike.lecteur.render

import org.apache.poi.hslf.usermodel.HSLFGroupShape
import org.apache.poi.hslf.usermodel.HSLFPictureShape
import org.apache.poi.hslf.usermodel.HSLFShape
import org.apache.poi.hslf.usermodel.HSLFSlideShow
import org.apache.poi.hslf.usermodel.HSLFTextShape
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.hwpf.extractor.WordExtractor
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.util.CellRangeAddress
import java.io.File
import java.util.Locale

/** Anciens formats binaires Microsoft Office : .doc, .xls, .ppt (via Apache POI). */
object LegacyOfficeRenderer {

    private fun prepare() {
        // Évite que log4j cherche une implémentation absente sur Android.
        System.setProperty("log4j2.loggerContextFactory", "org.apache.logging.log4j.simple.SimpleLoggerContextFactory")
        System.setProperty("log4j2.disable.jmx", "true")
    }

    private val CONTROL = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]")

    fun doc(file: File): String {
        prepare()
        val paras = file.inputStream().use { WordExtractor(it).use { ex -> ex.paragraphText } }
        val sb = StringBuilder()
        for (raw in paras) {
            val t = raw.replace("\u0007", "\t").replace("\r", "").replace("\n", "")
                .replace(Regex("\\u0013[^\\u0014\\u0015]*\\u0014"), "") // codes de champ
                .replace(CONTROL, "")
            if (t.isBlank()) sb.append("<div class=\"p\">&nbsp;</div>")
            else sb.append("<div class=\"p\">").append(Html.esc(t).replace("\t", "&emsp;")).append("</div>")
        }
        return Html.page("<div class=\"doc\">" + Html.note("Ancien format Word (.doc) : seul le texte est affiché.") + sb + "</div>")
    }

    fun xls(file: File): String {
        prepare()
        val body = StringBuilder()
        file.inputStream().use { input ->
            HSSFWorkbook(input).use { wb ->
                val fmt = DataFormatter(Locale.FRANCE)
                val idx = (0 until wb.numberOfSheets).filter { !wb.isSheetHidden(it) && !wb.isSheetVeryHidden(it) }
                if (idx.size > 1) {
                    body.append("<div class=\"nav\">")
                    idx.forEachIndexed { k, i -> body.append("<a href=\"#s$k\">").append(Html.esc(wb.getSheetName(i))).append("</a>") }
                    body.append("</div>")
                }
                idx.forEachIndexed { k, i ->
                    val sheet = wb.getSheetAt(i)
                    body.append("<h2 class=\"sname\" id=\"s$k\">").append(Html.esc(wb.getSheetName(i))).append("</h2>")
                    val data = SheetData()
                    for (row in sheet) {
                        for (cell in row) {
                            val (text, num) = cellText(cell, fmt)
                            data.put(row.rowNum, cell.columnIndex, SheetCell(text, num))
                        }
                    }
                    for (m: CellRangeAddress in sheet.mergedRegions) {
                        data.merges.add(intArrayOf(m.firstRow, m.firstColumn, m.lastRow, m.lastColumn))
                    }
                    SheetHtml.render(data, body)
                }
            }
        }
        return Html.page(body)
    }

    private fun cellText(cell: Cell, fmt: DataFormatter): Pair<String, Boolean> = try {
        when (cell.cellType) {
            CellType.FORMULA -> when (cell.cachedFormulaResultType) {
                CellType.NUMERIC -> fmt.formatRawCellContents(
                    cell.numericCellValue, cell.cellStyle.dataFormat.toInt(), cell.cellStyle.dataFormatString
                ) to true
                CellType.STRING -> cell.richStringCellValue.string to false
                CellType.BOOLEAN -> (if (cell.booleanCellValue) "VRAI" else "FAUX") to false
                else -> "#ERREUR" to false
            }
            CellType.NUMERIC -> fmt.formatCellValue(cell) to true
            CellType.BOOLEAN -> (if (cell.booleanCellValue) "VRAI" else "FAUX") to false
            else -> fmt.formatCellValue(cell) to false
        }
    } catch (e: Exception) {
        "" to false
    }

    fun ppt(file: File, resDir: File): String {
        prepare()
        val sb = StringBuilder()
        var imgCount = 0
        file.inputStream().use { input ->
            HSLFSlideShow(input).use { show ->
                show.slides.forEachIndexed { i, slide ->
                    sb.append("<div class=\"card\"><div class=\"slabel\">Diapositive ").append(i + 1).append("</div>")
                    fun walk(shapes: List<HSLFShape>) {
                        for (s in shapes) {
                            try {
                                when (s) {
                                    is HSLFGroupShape -> walk(s.shapes)
                                    is HSLFTextShape -> {
                                        val t = s.text?.replace("\u000B", "\n")?.replace("\r", "\n")?.trim()
                                        if (!t.isNullOrEmpty()) {
                                            for (line in t.split('\n')) {
                                                sb.append("<div class=\"p\">").append(if (line.isBlank()) "&nbsp;" else Html.esc(line)).append("</div>")
                                            }
                                        }
                                    }
                                    is HSLFPictureShape -> {
                                        val pd = s.pictureData ?: continue
                                        val ext = when (pd.contentType) {
                                            "image/png" -> "png"
                                            "image/jpeg" -> "jpg"
                                            "image/gif" -> "gif"
                                            "image/bmp", "image/x-ms-bmp" -> "bmp"
                                            else -> null
                                        } ?: continue
                                        resDir.mkdirs()
                                        val name = "ppt_${imgCount++}.$ext"
                                        File(resDir, name).writeBytes(pd.data)
                                        sb.append("<img src=\"res/").append(name).append("\">")
                                    }
                                }
                            } catch (_: Exception) {
                            }
                        }
                    }
                    walk(slide.shapes)
                    sb.append("</div>")
                }
            }
        }
        return Html.page(Html.note("Ancien format PowerPoint (.ppt) : texte et images uniquement.") + sb, "gray")
    }
}
