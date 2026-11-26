package com.spendcalci

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Writes payments to a formatted .xlsx (Office Open XML, built without external libraries). */
object PaymentExport {
    private const val NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val XML_HEADER = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"""

    private const val S_TEXT = 0
    private const val S_HEADER = 1
    private const val S_MONEY = 2
    private const val S_DATE = 3
    private const val S_TOTAL = 4
    private const val S_TOTAL_LABEL = 5

    private val HEADERS = listOf(
        "Date & time", "Merchant / payee", "Amount (₹)", "My share (₹)", "Payment method", "Card / account",
        "Category", "Note", "Split", "Split with", "Others' share (₹)", "Reference",
    )
    private val WIDTHS = listOf(20, 28, 14, 14, 16, 24, 16, 22, 9, 30, 18, 22)
    private val excelEpoch = LocalDateTime.of(1899, 12, 30, 0, 0)
    private val fileDate = DateTimeFormatter.ofPattern("yyyyMMdd")

    private sealed interface Cell {
        data class Str(val value: String, val style: Int = S_TEXT) : Cell
        data class Num(val value: Double, val style: Int) : Cell
        data class Formula(val f: String, val style: Int) : Cell
        data class Empty(val style: Int) : Cell
    }

    /** Writes [txns] (already limited to the chosen period) and returns the file. */
    fun write(ctx: Context, txns: List<Txn>, from: LocalDate, to: LocalDate): File {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "Kharcha_${from.format(fileDate)}_${to.format(fileDate)}.xlsx")
        val rows = txns.sortedBy { it.timestamp }
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            zip.put("[Content_Types].xml", CONTENT_TYPES)
            zip.put("_rels/.rels", ROOT_RELS)
            zip.put("xl/workbook.xml", workbookXml(rows.size + 1))
            zip.put("xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
            zip.put("xl/styles.xml", STYLES)
            zip.put("xl/worksheets/sheet1.xml", sheetXml(rows))
        }
        return file
    }

    fun share(ctx: Context, file: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "Export payments").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun sheetXml(rows: List<Txn>): String {
        val last = rows.size + 1
        val end = maxOf(last, 2)
        val body = StringBuilder()
        body.append(row(1, HEADERS.map { Cell.Str(it, S_HEADER) }, height = 32))
        rows.forEachIndexed { i, t -> body.append(row(i + 2, cellsOf(t))) }
        body.append(
            row(
                last + 2,
                listOf(
                    Cell.Str("Total", S_TOTAL_LABEL),
                    Cell.Empty(S_TOTAL_LABEL),
                    Cell.Formula("SUM(C2:C$end)", S_TOTAL),
                    Cell.Formula("SUM(D2:D$end)", S_TOTAL),
                    Cell.Empty(S_TOTAL_LABEL), Cell.Empty(S_TOTAL_LABEL), Cell.Empty(S_TOTAL_LABEL),
                    Cell.Empty(S_TOTAL_LABEL), Cell.Empty(S_TOTAL_LABEL), Cell.Empty(S_TOTAL_LABEL),
                    Cell.Formula("SUM(K2:K$end)", S_TOTAL),
                    Cell.Empty(S_TOTAL_LABEL),
                ),
            ),
        )
        return buildString {
            append(XML_HEADER)
            append("<worksheet xmlns=\"$NS\">")
            append("<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>")
            append("<sheetFormatPr defaultRowHeight=\"15\"/>")
            append("<cols>")
            WIDTHS.forEachIndexed { i, w -> append("<col min=\"${i + 1}\" max=\"${i + 1}\" width=\"$w\" customWidth=\"1\"/>") }
            append("</cols>")
            append("<sheetData>").append(body).append("</sheetData>")
            append("<autoFilter ref=\"A1:L$last\"/>")
            append("</worksheet>")
        }
    }

    private fun cellsOf(t: Txn): List<Cell> {
        val card = when {
            t.bucket().startsWith("CARD:") -> {
                val s = t.bucket().removePrefix("CARD:")
                val name = cardName(s)
                if (name == "Card $s") name else "$name (••$s)"
            }
            t.account.isBlank() || t.account == "manual" -> "—"
            else -> "A/c ${t.account}"
        }
        val members = t.members()
        return listOf(
            Cell.Num(excelSerial(t.timestamp), S_DATE),
            Cell.Str(t.merchant),
            Cell.Num(t.amount, S_MONEY),
            Cell.Num(t.myShare(), S_MONEY),
            Cell.Str(methodName(t.source)),
            Cell.Str(card),
            Cell.Str(t.category ?: "Unlabelled"),
            Cell.Str(t.note.orEmpty()),
            Cell.Str(if (t.split) "Yes" else "No"),
            Cell.Str(members.joinToString(", ") { it.name }),
            if (t.split) Cell.Num(members.sumOf { it.owed }, S_MONEY) else Cell.Empty(S_MONEY),
            Cell.Str(t.hash),
        )
    }

    private fun row(index: Int, cells: List<Cell>, height: Int? = null): String = buildString {
        append("<row r=\"$index\"")
        if (height != null) append(" ht=\"$height\" customHeight=\"1\"")
        append(">")
        cells.forEachIndexed { col, c ->
            val ref = "${'A' + col}$index"
            append(
                when (c) {
                    is Cell.Str -> "<c r=\"$ref\" s=\"${c.style}\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(c.value)}</t></is></c>"
                    is Cell.Num -> "<c r=\"$ref\" s=\"${c.style}\"><v>${BigDecimal.valueOf(c.value).toPlainString()}</v></c>"
                    is Cell.Formula -> "<c r=\"$ref\" s=\"${c.style}\"><f>${esc(c.f)}</f></c>"
                    is Cell.Empty -> "<c r=\"$ref\" s=\"${c.style}\"/>"
                }
            )
        }
        append("</row>")
    }

    private fun workbookXml(lastRow: Int): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<workbook xmlns=\"$NS\" xmlns:r=\"$REL_NS\">" +
            "<sheets><sheet name=\"Payments\" sheetId=\"1\" r:id=\"rId1\"/></sheets>" +
            "<definedNames><definedName name=\"_xlnm._FilterDatabase\" localSheetId=\"0\" hidden=\"1\">" +
            "Payments!\$A\$1:\$L\$$lastRow</definedName></definedNames>" +
            "</workbook>"

    private fun excelSerial(ts: Long): Double {
        val ldt = Instant.ofEpochMilli(ts).atZone(zone).toLocalDateTime()
        return Duration.between(excelEpoch, ldt).toMillis() / 86_400_000.0
    }

    private fun methodName(s: String) = when (s) {
        "UPI" -> "UPI"
        "CARD" -> "Card"
        "CASH" -> "Cash"
        "OTHER" -> "Other"
        else -> s
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        .filter { it == '\t' || it == '\n' || it == '\r' || it >= ' ' }

    private fun ZipOutputStream.put(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>"""

    private const val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""

    private const val WORKBOOK_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>"""

    private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><numFmts count="2"><numFmt numFmtId="164" formatCode="#,##0.00"/><numFmt numFmtId="165" formatCode="dd-mmm-yyyy h:mm AM/PM"/></numFmts><fonts count="3"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF1F2A44"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="2"><border><left/><right/><top/><bottom/><diagonal/></border><border><left/><right/><top style="thin"><color rgb="FF777777"/></top><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="6"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="2" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1" applyAlignment="1"><alignment vertical="center" wrapText="1"/></xf><xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/><xf numFmtId="165" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/><xf numFmtId="164" fontId="1" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyFont="1" applyBorder="1"/><xf numFmtId="0" fontId="1" fillId="0" borderId="1" xfId="0" applyFont="1" applyBorder="1"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>"""
}
