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
import java.util.Calendar
import java.util.Date
import java.util.Locale

object Export {
    private val cs = Locale("cs", "CZ")

    /** Označení vozidel (SPZ nebo název) podle id; nastavuje se před exportem. */
    var plates: Map<Long, String> = emptyMap()

    fun statusName(s: String) = when (s) {
        "S" -> "Služební"
        "P" -> "Soukromá"
        else -> "Nezařazeno"
    }

    fun f1(v: Double): String = String.format(cs, "%.1f", v)

    private fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""

    private fun money(v: Double): String = String.format(cs, "%.2f", v)

    /** Konec dne, do kterého tankování patří – v exportu se řadí za jízdy téhož dne. */
    private fun dayEnd(ts: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = ts
        c.set(Calendar.HOUR_OF_DAY, 23); c.set(Calendar.MINUTE, 59); c.set(Calendar.SECOND, 59)
        return c.timeInMillis
    }

    /** Jízdy a tankování v jednom časovém sledu: (klíč řazení, jízda nebo účtenka). */
    private fun merged(trips: List<Trip>, fuel: List<Fuel>): List<Pair<Long, Any>> =
        (trips.map { Pair<Long, Any>(it.startTs, it) } + fuel.map { Pair<Long, Any>(it.ts, it) })
            .sortedBy { it.first }

    /** CSV se středníky a v kódování, které Excel otevře správně i s češtinou. */
    fun csv(out: OutputStream, trips: List<Trip>, fuel: List<Fuel>) {
        val d = SimpleDateFormat("d.M.yyyy", cs)
        val h = SimpleDateFormat("HH:mm", cs)
        val sb = StringBuilder("\uFEFF")
        sb.append(
            "Datum;Vozidlo;Typ;Začátek;Konec;Start;Start GPS;Cíl;Cíl GPS;Km;Průměrná rychlost (km/h);Doba (min);Status;Záznam;Poznámka;" +
                "Čerpací stanice;Litry;Cena vč. DPH;Cena bez DPH\r\n"
        )
        for ((_, item) in merged(trips, fuel)) {
            val row = if (item is Trip) listOf(
                d.format(Date(item.startTs)), plates[item.vehicleId] ?: "", "Jízda", h.format(Date(item.startTs)), h.format(Date(item.endTs)),
                item.startPlace, Places.coords(item.sLat, item.sLon), item.endPlace, Places.coords(item.eLat, item.eLon),
                f1(item.km), Math.round(item.avgKmh).toString(), item.minutes.toString(),
                statusName(item.status), if (item.manual) "ručně" else "automaticky", item.note, "", "", "", ""
            ) else {
                val f = item as Fuel
                listOf(
                    d.format(Date(f.ts)), plates[f.vehicleId] ?: "", "Tankování", h.format(Date(f.ts)), "", "", "", "", "", "", "", "", "", "", "",
                    f.place, money(f.liters), money(f.priceVat), money(f.priceNoVat)
                )
            }
            sb.append(row.joinToString(";") { q(it) }).append("\r\n")
        }
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
    }

    private const val W = 842
    private const val H = 595
    private const val M = 32f
    private val cols = listOf(
        "Datum" to 58f, "Čas" to 70f, "Start" to 110f, "Start GPS" to 85f, "Cíl" to 110f, "Cíl GPS" to 85f,
        "Km" to 42f, "Ø km/h" to 42f, "Min" to 34f, "Status" to 58f, "Vůz, poznámka" to 84f
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

    private fun header(c: Canvas, title: String?, summary: List<String>, bold: TextPaint, line: Paint): Float {
        var y = 40f
        if (title != null) {
            val big = TextPaint(bold); big.textSize = 15f
            c.drawText(title, M, y, big)
            y += 16f
            val small = TextPaint(bold); small.typeface = Typeface.DEFAULT
            for (s in summary) { c.drawText(s, M, y, small); y += 13f }
            y += 8f
        }
        row(c, y, cols.map { it.first }, bold)
        c.drawLine(M, y + 5f, W - M, y + 5f, line)
        return y + 18f
    }

    /** PDF na šířku A4: nadpis, souhrn a tabulka jízd s tankováním u příslušných dnů, podle potřeby na více stran. */
    fun pdf(out: OutputStream, title: String, trips: List<Trip>, fuel: List<Fuel>, extra: List<String>) {
        val d = SimpleDateFormat("d.M.yyyy", cs)
        val h = SimpleDateFormat("HH:mm", cs)
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG); p.textSize = 9f; p.color = Color.BLACK
        val bold = TextPaint(p); bold.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        val line = Paint(); line.color = Color.GRAY; line.strokeWidth = 0.6f
        val km = trips.sumOf { it.km }
        val kmS = trips.filter { it.status == "S" }.sumOf { it.km }
        val kmP = trips.filter { it.status == "P" }.sumOf { it.km }
        val summary = listOf(
            "Jízd: ${trips.size}   Celkem: ${f1(km)} km   Služebně: ${f1(kmS)} km   Soukromě: ${f1(kmP)} km"
        ) + extra

        val doc = PdfDocument()
        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
        var y = header(page.canvas, title, summary, bold, line)
        for ((_, item) in merged(trips, fuel)) {
            if (y > H - 36f) {
                doc.finishPage(page)
                pageNo++
                page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
                y = header(page.canvas, null, emptyList(), bold, line)
            }
            if (item is Trip) {
                row(
                    page.canvas, y, listOf(
                        d.format(Date(item.startTs)), h.format(Date(item.startTs)) + "–" + h.format(Date(item.endTs)),
                        item.startPlace, Places.coords(item.sLat, item.sLon), item.endPlace, Places.coords(item.eLat, item.eLon),
                        f1(item.km), Math.round(item.avgKmh).toString(), item.minutes.toString(), statusName(item.status),
                        listOf(plates[item.vehicleId] ?: "", item.note).filter { it.isNotBlank() }.joinToString(" · ")
                    ), p
                )
            } else {
                val f = item as Fuel
                // Tankování: jeden tučný řádek přes šířku tabulky pod jízdami daného dne.
                page.canvas.drawText(d.format(Date(f.ts)), M, y, bold)
                page.canvas.drawText(
                    h.format(Date(f.ts)) + "   Tankování" + (plates[f.vehicleId]?.let { " ($it)" } ?: "") + ": " + f.place.ifBlank { "neuvedeno" } + "   " + money(f.liters) + " l   " +
                        money(f.priceVat) + " Kč vč. DPH   " + money(f.priceNoVat) + " Kč bez DPH",
                    M + cols[0].second, y, bold
                )
            }
            y += 16f
        }
        doc.finishPage(page)
        doc.writeTo(out)
        doc.close()
    }
}
