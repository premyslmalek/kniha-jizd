package cz.knihajizd

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.TextPaint
import android.text.TextUtils
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Export {
    private val cs = Locale("cs", "CZ")

    fun statusName(s: String) = when (s) {
        "S" -> "Služební"
        "P" -> "Soukromá"
        else -> "Nezařazeno"
    }

    fun f1(v: Double): String = String.format(cs, "%.1f", v)

    private fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""

    /** CSV se středníky a v kódování, které Excel otevře správně i s češtinou. */
    fun csv(out: OutputStream, trips: List<Trip>) {
        val d = SimpleDateFormat("d.M.yyyy", cs)
        val h = SimpleDateFormat("HH:mm", cs)
        val sb = StringBuilder("﻿")
        sb.append("Datum;Začátek;Konec;Start;Start GPS;Cíl;Cíl GPS;Km;Průměrná rychlost (km/h);Doba (min);Status;Záznam;Poznámka\r\n")
        for (t in trips.sortedBy { it.startTs }) {
            val row = listOf(
                d.format(Date(t.startTs)), h.format(Date(t.startTs)), h.format(Date(t.endTs)),
                t.startPlace, Places.coords(t.sLat, t.sLon), t.endPlace, Places.coords(t.eLat, t.eLon),
                f1(t.km), Math.round(t.avgKmh).toString(), t.minutes.toString(),
                statusName(t.status), if (t.manual) "ručně" else "automaticky", t.note
            )
            sb.append(row.joinToString(";") { q(it) }).append("\r\n")
        }
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
    }

    private const val W = 842
    private const val H = 595
    private const val M = 32f
    private val cols = listOf(
        "Datum" to 58f, "Čas" to 70f, "Start" to 120f, "Start GPS" to 90f, "Cíl" to 120f, "Cíl GPS" to 90f,
        "Km" to 42f, "Ø km/h" to 42f, "Min" to 34f, "Status" to 58f, "Poznámka" to 54f
    )

    private fun row(c: Canvas, y: Float, values: List<String>, paint: TextPaint) {
        var x = M
        for (i in cols.indices) {
            val w = cols[i].second
            val txt = TextUtils.ellipsize(values[i], paint, w - 6f, TextUtils.TruncateAt.END).toString()
            c.drawText(txt, x, y, paint)
            x += w
        }
    }

    private fun header(c: Canvas, title: String?, summary: String?, bold: TextPaint, line: Paint): Float {
        var y = 40f
        if (title != null) {
            val big = TextPaint(bold); big.textSize = 15f
            c.drawText(title, M, y, big)
            y += 16f
            if (summary != null) {
                val small = TextPaint(bold); small.typeface = Typeface.DEFAULT
                c.drawText(summary, M, y, small)
                y += 20f
            }
        }
        row(c, y, cols.map { it.first }, bold)
        c.drawLine(M, y + 5f, W - M, y + 5f, line)
        return y + 18f
    }

    /** PDF na šířku A4: nadpis, souhrn a tabulka jízd, podle potřeby na více stran. */
    fun pdf(out: OutputStream, title: String, trips: List<Trip>) {
        val d = SimpleDateFormat("d.M.yyyy", cs)
        val h = SimpleDateFormat("HH:mm", cs)
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG); p.textSize = 9f; p.color = Color.BLACK
        val bold = TextPaint(p); bold.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        val line = Paint(); line.color = Color.GRAY; line.strokeWidth = 0.6f
        val sorted = trips.sortedBy { it.startTs }
        val km = sorted.sumOf { it.km }
        val kmS = sorted.filter { it.status == "S" }.sumOf { it.km }
        val kmP = sorted.filter { it.status == "P" }.sumOf { it.km }
        val summary = "Jízd: ${sorted.size}   Celkem: ${f1(km)} km   Služebně: ${f1(kmS)} km   Soukromě: ${f1(kmP)} km"

        val doc = PdfDocument()
        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
        var y = header(page.canvas, title, summary, bold, line)
        for (t in sorted) {
            if (y > H - 36f) {
                doc.finishPage(page)
                pageNo++
                page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
                y = header(page.canvas, null, null, bold, line)
            }
            row(
                page.canvas, y, listOf(
                    d.format(Date(t.startTs)), h.format(Date(t.startTs)) + "–" + h.format(Date(t.endTs)),
                    t.startPlace, Places.coords(t.sLat, t.sLon), t.endPlace, Places.coords(t.eLat, t.eLon),
                    f1(t.km), Math.round(t.avgKmh).toString(), t.minutes.toString(), statusName(t.status), t.note
                ), p
            )
            y += 16f
        }
        doc.finishPage(page)
        doc.writeTo(out)
        doc.close()
    }
}
