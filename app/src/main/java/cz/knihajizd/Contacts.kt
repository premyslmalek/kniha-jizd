package cz.knihajizd

import java.nio.charset.Charset

/** Položka adresáře cílů: firma a její adresa. */
data class Contact(
    val id: Long, val name: String, val street: String, val city: String, val zip: String,
    /** Vzdálenost po dálnici a mimo dálnice v km; 0 = v souboru neuvedeno. */
    val kmHighway: Double = 0.0, val kmOther: Double = 0.0
) {
    /** Text, který se zapíše jako cíl jízdy. */
    val place: String get() = listOf(name, street, city).filter { it.isNotBlank() }.joinToString(", ")
    val address: String get() = listOf(street, listOf(zip, city).filter { it.isNotBlank() }.joinToString(" "))
        .filter { it.isNotBlank() }.joinToString(", ")
}

/** Načtení adresáře z CSV: název firmy, ulice s č.p., město, PSČ, vzdálenost po dálnici a mimo dálnice. */
object ContactsCsv {

    /** Soubory z Excelu bývají ve Windows-1250; UTF-8 se pozná podle toho, že jde dekódovat bez chyb. */
    fun decode(bytes: ByteArray): String {
        val utf = String(bytes, Charsets.UTF_8)
        val text = if (utf.contains('�')) String(bytes, Charset.forName("windows-1250")) else utf
        return text.removePrefix("﻿")
    }

    private fun splitLine(line: String, sep: Char): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            if (quoted) {
                if (ch == '"' && i + 1 < line.length && line[i + 1] == '"') { sb.append('"'); i++ }
                else if (ch == '"') quoted = false
                else sb.append(ch)
            } else if (ch == '"') quoted = true
            else if (ch == sep) { out.add(sb.toString().trim()); sb.setLength(0) }
            else sb.append(ch)
            i++
        }
        out.add(sb.toString().trim())
        return out
    }

    private fun find(header: List<String>, vararg keys: String): Int =
        header.indexOfFirst { h -> keys.any { h.lowercase().contains(it) } }

    fun parse(text: String): List<Contact> {
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()
        val sep = if (lines[0].count { it == ';' } >= lines[0].count { it == ',' }) ';' else ','
        val header = splitLine(lines[0], sep)
        var iName = find(header, "název", "nazev", "firma", "firm")
        var iStreet = find(header, "ulic", "adres")
        var iCity = find(header, "měst", "mest", "obec")
        var iZip = find(header, "psč", "psc")
        var iOther = header.indexOfFirst { h -> h.lowercase().let { (it.contains("dáln") || it.contains("daln")) && it.contains("mimo") } }
        var iHw = header.indexOfFirst { h -> h.lowercase().let { (it.contains("dáln") || it.contains("daln")) && !it.contains("mimo") } }
        val hasHeader = iName >= 0
        // bez rozpoznané hlavičky se bere pořadí sloupců: název, ulice, město, PSČ
        if (!hasHeader) { iName = 0; iStreet = 1; iCity = 2; iZip = 3; iHw = 4; iOther = 5 }
        val out = ArrayList<Contact>()
        for (line in if (hasHeader) lines.drop(1) else lines) {
            val c = splitLine(line, sep)
            val get = { i: Int -> if (i >= 0 && i < c.size) c[i] else "" }
            val name = get(iName)
            val km = { i: Int -> Regex("\\d+([.,]\\d+)?").find(get(i).replace(" ", ""))?.value?.replace(',', '.')?.toDoubleOrNull() ?: 0.0 }
            if (name.isNotBlank()) out.add(Contact(0, name, get(iStreet), get(iCity), get(iZip), km(iHw), km(iOther)))
        }
        return out
    }
}
