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
    val photo: String = "",
    val vehicleId: Long = 0,
    /** Adresa čerpací stanice. */
    val address: String = ""
) {
    /** Název stanice s adresou, jak se zobrazuje v přehledu a exportu. */
    val place: String get() = listOf(station, address).filter { it.isNotBlank() }.joinToString(", ")
}

/** Vytažení údajů z textu přečteného z fotky účtenky. Výsledek je jen návrh, uživatel ho před uložením kontroluje. */
object Receipts {
    private val brands = listOf(
        "MOL", "Shell", "OMV", "ORLEN", "Benzina", "EuroOil", "Tank ONO", "Robin Oil", "KM Prona",
        "Globus", "Tesco", "Makro", "Lukoil", "Agip", "Pap Oil", "Čepro", "Avia", "Terno", "Albert"
    )
    private val dateRx = Regex("\\b(\\d{1,2})\\s?[./]\\s?(\\d{1,2})\\s?[./]\\s?(\\d{4}|\\d{2})\\b")
    private val numRx = Regex("\\d{1,3}(?:[ \\u00A0]\\d{3})+[.,]\\d{2}|\\d{1,6}[.,]\\d{2}")
    // "l" čtečka často zamění za "I" nebo "1", proto se bere i to; "Kč/l" (cena za litr) se vynechává.
    private val litersRx = Regex("(?<![\\d.,])(\\d{1,3}[.,]\\d{1,3})\\s*(?:ltr|litr[ůuy]?|[lLI])(?![\\w/])")
    private val perLiterRx = Regex("(\\d{1,3}[.,]\\d{1,3})\\s*(?:Kč|Kc|CZK)?\\s*/\\s*[lLI1]")
    private val anyNumRx = Regex("(?<![\\d.,])\\d{1,3}[.,]\\d{1,3}(?![\\d.,])")
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

    /**
     * Litry: nejspolehlivější je dvojice čísel "množství × cena za litr", jejichž součin dává celkovou cenu –
     * nezávisí na rozložení účtenky ani na tom, jestli se správně přečetlo písmeno "l".
     * Teprve když taková dvojice není, hledá se číslo následované jednotkou.
     */
    private fun findLiters(text: String, total: Double): Double {
        val nums = anyNumRx.findAll(text).mapNotNull { toD(it.value) }.toList()
        val withUnit = litersRx.findAll(text).mapNotNull { toD(it.groupValues[1]) }.toSet()
        val unitPrices = perLiterRx.findAll(text).mapNotNull { toD(it.groupValues[1]) }.toSet()
        if (total > 0.0) {
            // Součin je souměrný, takže dvojici (množství, cena za litr) je potřeba ještě správně otočit:
            // rozhodne jednotka u čísla ("l" = litry, "Kč/l" = cena), jinak pořadí na účtence.
            var best = 0.0
            var bestScore = -1
            for (q in nums) {
                if (q < 1.0 || q > 300.0) continue
                for (p in nums) {
                    if (p < 15.0 || p > 90.0 || q == p) continue
                    if (Math.abs(q * p - total) / total >= 0.006) continue
                    val score = (if (q in withUnit) 2 else 0) + (if (p in unitPrices) 2 else 0) -
                        (if (q in unitPrices) 2 else 0) - (if (p in withUnit) 2 else 0)
                    if (score > bestScore) { bestScore = score; best = q }
                }
            }
            if (best > 0.0) return best
        }
        return litersRx.findAll(text).mapNotNull { toD(it.groupValues[1]) }.firstOrNull { it in 1.0..300.0 } ?: 0.0
    }

    private val pscRx = Regex("\\b\\d{3}\\s?\\d{2}\\s+\\p{L}[\\p{L} .\\-]+")
    private val streetRx = Regex("^[\\p{L}][\\p{L} .\\-]*\\s\\d+[\\w/]*$")
    private val siteWords = listOf("provozov", "čerpací stanice", "cerpaci stanice", "čs ", "stanice")

    /**
     * Adresa čerpací stanice: řádek s PSČ a obcí, případně s ulicí z řádku nad ním.
     * Na účtence bývá i sídlo firmy; přednost má adresa uvedená u slova "provozovna" nebo "čerpací stanice".
     */
    private fun findAddress(lines: List<String>): String {
        val idx = lines.indices.filter { pscRx.containsMatchIn(lines[it]) }
        if (idx.isEmpty()) return ""
        val near = idx.firstOrNull { i -> (maxOf(0, i - 2)..i).any { j -> siteWords.any { w -> lines[j].lowercase().contains(w) } } }
        val i = near ?: idx.first()
        val line = lines[i]
        val m = pscRx.find(line) ?: return ""
        // ulice bývá buď na stejném řádku před PSČ, nebo o řádek výš
        val before = line.substring(0, m.range.first).trim().trim(',').substringAfter(':').trim()
        val street = if (before.isNotEmpty()) before
        else if (i > 0 && streetRx.matches(lines[i - 1].substringAfter(':').trim())) lines[i - 1].substringAfter(':').trim()
        else ""
        return listOf(street, m.value.trim()).filter { it.isNotBlank() }.joinToString(", ").take(80)
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

        var total = near(lines, totalWords, noVatWords + paidWords).maxOrNull() ?: 0.0
        if (total == 0.0) {
            total = lines.filter { l -> paidWords.none { l.lowercase().contains(it) } }
                .flatMap { numbers(it) }.filter { it < 100000.0 }.maxOrNull() ?: 0.0
        }

        val liters = findLiters(text, total)

        var noVat = near(lines, noVatWords, emptyList()).filter { total == 0.0 || it < total }.maxOrNull() ?: 0.0
        // U pohonných hmot je DPH 21 %; když základ na účtence nejde přečíst, dopočítá se.
        if (noVat == 0.0 && total > 0.0) noVat = Math.round(total / 1.21 * 100.0) / 100.0

        return Fuel(0, ts, station, liters, total, noVat, address = findAddress(lines))
    }
}
