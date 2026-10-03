package cz.knihajizd

import java.util.Calendar

/** Účtenka za tankování. */
data class Fuel(
    val id: Long,
    val ts: Long,
    val station: String,
    val liters: Double,
    val priceVat: Double,
    val priceNoVat: Double,
    /** Cesta k uložené fotce účtenky v úložišti aplikace; prázdná u ručně zadané účtenky. */
    val photo: String = ""
)

/** Vytažení údajů z textu přečteného z fotky účtenky. Výsledek je jen návrh, uživatel ho před uložením kontroluje. */
object Receipts {
    private val brands = listOf(
        "MOL", "Shell", "OMV", "ORLEN", "Benzina", "EuroOil", "Tank ONO", "Robin Oil", "KM Prona",
        "Globus", "Tesco", "Makro", "Lukoil", "Agip", "Pap Oil", "Čepro", "Avia", "Terno", "Albert"
    )
    private val dateRx = Regex("\\b(\\d{1,2})\\s?[./]\\s?(\\d{1,2})\\s?[./]\\s?(\\d{4}|\\d{2})\\b")
    private val numRx = Regex("\\d{1,3}(?:[ \\u00A0]\\d{3})+[.,]\\d{2}|\\d{1,6}[.,]\\d{2}")
    private val litersRx = Regex("(\\d{1,3}[.,]\\d{1,3})\\s*(?:ltr|litr[ůuy]?|l)\\b", RegexOption.IGNORE_CASE)
    private val totalWords = listOf("celkem", "k úhradě", "k uhrade", "úhrada", "uhrada", "suma", "total", "k platbě", "k platbe")
    private val paidWords = listOf("hotov", "vráceno", "vraceno", "přijato", "prijato", "karta", "zaplaceno")
    private val noVatWords = listOf("bez dph", "základ", "zaklad")

    private fun toD(s: String): Double? =
        s.replace(" ", "").replace(" ", "").replace(',', '.').toDoubleOrNull()

    private fun numbers(line: String): List<Double> = numRx.findAll(line).mapNotNull { toD(it.value) }.toList()

    /** Čísla na řádcích s některým z klíčových slov; když na řádku číslo není, zkusí se řádek pod ním. */
    private fun near(lines: List<String>, words: List<String>, skip: List<String>): List<Double> {
        val out = ArrayList<Double>()
        for (i in lines.indices) {
            val low = lines[i].lowercase()
            if (words.none { low.contains(it) } || skip.any { low.contains(it) }) continue
            val here = numbers(lines[i])
            if (here.isNotEmpty()) out.addAll(here)
            else if (i + 1 < lines.size) out.addAll(numbers(lines[i + 1]))
        }
        return out
    }

    fun parse(text: String): Fuel {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

        var ts = System.currentTimeMillis()
        val dm = dateRx.find(text)
        if (dm != null) {
            val d = dm.groupValues[1].toInt()
            val m = dm.groupValues[2].toInt()
            var y = dm.groupValues[3].toInt()
            if (y < 100) y += 2000
            if (d in 1..31 && m in 1..12 && y in 2000..2100) {
                val c = Calendar.getInstance()
                c.clear(); c.set(y, m - 1, d, 12, 0, 0)
                ts = c.timeInMillis
            }
        }

        var station = ""
        var best = Int.MAX_VALUE
        for (b in brands) {
            val m = Regex("\\b" + Regex.escape(b) + "\\b", RegexOption.IGNORE_CASE).find(text)
            if (m != null && m.range.first < best) { best = m.range.first; station = b }
        }
        if (station.isEmpty()) station = lines.firstOrNull()?.take(40) ?: ""

        val liters = litersRx.findAll(text).mapNotNull { toD(it.groupValues[1]) }
            .firstOrNull { it in 1.0..300.0 } ?: 0.0

        var total = near(lines, totalWords, noVatWords + paidWords).maxOrNull() ?: 0.0
        if (total == 0.0) {
            total = lines.filter { l -> paidWords.none { l.lowercase().contains(it) } }
                .flatMap { numbers(it) }.filter { it < 100000.0 }.maxOrNull() ?: 0.0
        }

        var noVat = near(lines, noVatWords, emptyList()).filter { total == 0.0 || it < total }.maxOrNull() ?: 0.0
        // U pohonných hmot je DPH 21 %; když základ na účtence nejde přečíst, dopočítá se.
        if (noVat == 0.0 && total > 0.0) noVat = Math.round(total / 1.21 * 100.0) / 100.0

        return Fuel(0, ts, station, liters, total, noVat)
    }
}
