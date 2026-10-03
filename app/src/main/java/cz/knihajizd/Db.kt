package cz.knihajizd

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.location.Geocoder
import java.util.Calendar
import java.util.Locale

data class Trip(
    val id: Long,
    val startTs: Long,
    val endTs: Long,
    val startPlace: String,
    val endPlace: String,
    val distM: Double,
    val status: String, // S = služební, P = soukromá, N = nezařazeno
    val manual: Boolean,
    val note: String,
    val sLat: Double,
    val sLon: Double,
    val eLat: Double,
    val eLon: Double
) {
    val km: Double get() = distM / 1000.0
    val minutes: Long get() = Math.round((endTs - startTs) / 60000.0)
    val avgKmh: Double get() = if (endTs > startTs) distM / ((endTs - startTs) / 1000.0) * 3.6 else 0.0
}

class Reading(val ym: String, val km: Int, val ts: Long)

/**
 * Stav tachometru v čase. Vychází z posledního skutečného stavu zadaného uživatelem
 * a přičítá jízdy zaznamenané po něm; bez zadaného stavu z počátečního stavu v nastavení.
 */
class Odo(private val db: Db, private val start: Int) {
    private val rs = db.readings()

    val available: Boolean get() = start > 0 || rs.isNotEmpty()

    fun reading(ym: String): Reading? = rs.firstOrNull { it.ym == ym }

    fun state(t: Long, exclude: String? = null): Double? {
        val list = rs.filter { it.ym != exclude }
        val r = list.lastOrNull { it.ts <= t }
        if (r != null) return r.km + db.kmBetween(r.ts, t)
        if (start > 0) return start + db.kmBefore(t)
        val first = list.firstOrNull() ?: return null
        return first.km - db.kmBetween(t, first.ts)
    }

    /** O kolik km se zadaný skutečný stav liší od stavu spočítaného ze zaznamenaných jízd. */
    fun adjustment(ym: String): Long? {
        val r = reading(ym) ?: return null
        val computed = state(r.ts, ym) ?: return null
        return Math.round(r.km - computed)
    }
}

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("nastaveni", Context.MODE_PRIVATE)
    var mode: String
        get() = sp.getString("mode", "auto") ?: "auto"
        set(v) = sp.edit().putString("mode", v).apply()
    var threshold: Int
        get() = sp.getInt("threshold", 40)
        set(v) = sp.edit().putInt("threshold", v).apply()
    var minKm: Int
        get() = sp.getInt("minKm", 2)
        set(v) = sp.edit().putInt("minKm", v).apply()
    /** Stav tachometru vozu (km) před první jízdou zaznamenanou v aplikaci. */
    var odoStart: Int
        get() = sp.getInt("odoStart", 0)
        set(v) = sp.edit().putInt("odoStart", v).apply()
    var stopMin: Int
        get() = sp.getInt("stopMin", 5)
        set(v) = sp.edit().putInt("stopMin", v).apply()
}

class Db(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "kniha.db", null, 4) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE trips(id INTEGER PRIMARY KEY AUTOINCREMENT, start_ts INTEGER, end_ts INTEGER, " +
                "start_lat REAL, start_lon REAL, end_lat REAL, end_lon REAL, start_place TEXT, end_place TEXT, " +
                "dist_m REAL, status TEXT, manual INTEGER, note TEXT)"
        )
        createReadings(db)
        createFuel(db)
    }

    private fun createFuel(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS fuel(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, station TEXT, " +
                "liters REAL, price_vat REAL, price_novat REAL, photo TEXT)"
        )
    }

    /** Účtenky za tankování v daném měsíci, nejnovější první. */
    fun fuelMonth(year: Int, month0: Int): List<Fuel> {
        val from = monthStart(year, month0)
        val to = if (month0 == 11) monthStart(year + 1, 0) else monthStart(year, month0 + 1)
        val out = ArrayList<Fuel>()
        readableDatabase.rawQuery(
            "SELECT id, ts, station, liters, price_vat, price_novat, photo FROM fuel WHERE ts >= ? AND ts < ? ORDER BY ts DESC, id DESC",
            arrayOf(from.toString(), to.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(
                Fuel(c.getLong(0), c.getLong(1), c.getString(2) ?: "", c.getDouble(3), c.getDouble(4), c.getDouble(5), c.getString(6) ?: "")
            )
        }
        return out
    }

    fun fuelSave(f: Fuel) {
        val v = ContentValues()
        v.put("ts", f.ts); v.put("station", f.station); v.put("liters", f.liters)
        v.put("price_vat", f.priceVat); v.put("price_novat", f.priceNoVat); v.put("photo", f.photo)
        if (f.id == 0L) writableDatabase.insert("fuel", null, v)
        else writableDatabase.update("fuel", v, "id = ?", arrayOf(f.id.toString()))
    }

    fun fuelDelete(id: Long) {
        writableDatabase.delete("fuel", "id = ?", arrayOf(id.toString()))
    }

    private fun createReadings(db: SQLiteDatabase) {
        // Skutečný stav tachometru zadaný uživatelem: jeden záznam na měsíc (ym = "2026-10").
        db.execSQL("CREATE TABLE IF NOT EXISTS odo(ym TEXT PRIMARY KEY, km INTEGER, ts INTEGER)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createReadings(db)
        if (oldVersion < 3) createFuel(db)
        else if (oldVersion < 4) db.execSQL("ALTER TABLE fuel ADD COLUMN photo TEXT")
    }

    fun readings(): List<Reading> {
        val out = ArrayList<Reading>()
        readableDatabase.rawQuery("SELECT ym, km, ts FROM odo ORDER BY ts", null).use { c ->
            while (c.moveToNext()) out.add(Reading(c.getString(0), c.getInt(1), c.getLong(2)))
        }
        return out
    }

    fun setReading(ym: String, km: Int, ts: Long) {
        val v = ContentValues(); v.put("ym", ym); v.put("km", km); v.put("ts", ts)
        writableDatabase.insertWithOnConflict("odo", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteReading(ym: String) {
        writableDatabase.delete("odo", "ym = ?", arrayOf(ym))
    }

    /** Součet km jízd, které začaly v intervalu <from, to). */
    fun kmBetween(from: Long, to: Long): Double {
        readableDatabase.rawQuery(
            "SELECT COALESCE(SUM(dist_m), 0) FROM trips WHERE start_ts >= ? AND start_ts < ?",
            arrayOf(from.toString(), to.toString())
        ).use { c -> return if (c.moveToFirst()) c.getDouble(0) / 1000.0 else 0.0 }
    }

    fun insert(
        startTs: Long, endTs: Long, sLat: Double, sLon: Double, eLat: Double, eLon: Double,
        startPlace: String, endPlace: String, distM: Double, status: String, manual: Boolean
    ): Long {
        val v = ContentValues()
        v.put("start_ts", startTs); v.put("end_ts", endTs)
        v.put("start_lat", sLat); v.put("start_lon", sLon); v.put("end_lat", eLat); v.put("end_lon", eLon)
        v.put("start_place", startPlace); v.put("end_place", endPlace)
        v.put("dist_m", distM); v.put("status", status); v.put("manual", if (manual) 1 else 0); v.put("note", "")
        return writableDatabase.insert("trips", null, v)
    }

    private fun read(c: Cursor) = Trip(
        c.getLong(0), c.getLong(1), c.getLong(2), c.getString(3) ?: "", c.getString(4) ?: "",
        c.getDouble(5), c.getString(6) ?: "N", c.getInt(7) == 1, c.getString(8) ?: "",
        c.getDouble(9), c.getDouble(10), c.getDouble(11), c.getDouble(12)
    )

    private val cols = "id, start_ts, end_ts, start_place, end_place, dist_m, status, manual, note, start_lat, start_lon, end_lat, end_lon"

    /** Jízdy v daném měsíci (month0 = 0..11), nejnovější první. */
    fun month(year: Int, month0: Int): List<Trip> {
        val cal = Calendar.getInstance()
        cal.clear(); cal.set(year, month0, 1, 0, 0, 0)
        val from = cal.timeInMillis
        cal.add(Calendar.MONTH, 1)
        val to = cal.timeInMillis
        val out = ArrayList<Trip>()
        readableDatabase.rawQuery(
            "SELECT $cols FROM trips WHERE start_ts >= ? AND start_ts < ? ORDER BY start_ts DESC",
            arrayOf(from.toString(), to.toString())
        ).use { c -> while (c.moveToNext()) out.add(read(c)) }
        return out
    }

    /** Součet km všech jízd, které začaly před daným okamžikem. */
    fun kmBefore(ts: Long): Double {
        readableDatabase.rawQuery(
            "SELECT COALESCE(SUM(dist_m), 0) FROM trips WHERE start_ts < ?", arrayOf(ts.toString())
        ).use { c -> return if (c.moveToFirst()) c.getDouble(0) / 1000.0 else 0.0 }
    }

    fun monthStart(year: Int, month0: Int): Long {
        val cal = Calendar.getInstance()
        cal.clear(); cal.set(year, month0, 1, 0, 0, 0)
        return cal.timeInMillis
    }

    fun get(id: Long): Trip? {
        readableDatabase.rawQuery("SELECT $cols FROM trips WHERE id = ?", arrayOf(id.toString())).use { c ->
            return if (c.moveToFirst()) read(c) else null
        }
    }

    fun setStatus(id: Long, status: String) {
        val v = ContentValues(); v.put("status", status)
        writableDatabase.update("trips", v, "id = ?", arrayOf(id.toString()))
    }

    fun setNote(id: Long, note: String) {
        val v = ContentValues(); v.put("note", note)
        writableDatabase.update("trips", v, "id = ?", arrayOf(id.toString()))
    }

    fun setPlaces(id: Long, startPlace: String, endPlace: String) {
        val v = ContentValues(); v.put("start_place", startPlace); v.put("end_place", endPlace)
        writableDatabase.update("trips", v, "id = ?", arrayOf(id.toString()))
    }

    fun delete(id: Long) {
        writableDatabase.delete("trips", "id = ?", arrayOf(id.toString()))
    }
}

object Places {
    // V české adrese stojí obec hned za PSČ: "Císařská 65, 798 07 Brodek u Prostějova, Česko".
    private val afterPsc = Regex("\\b\\d{3}\\s?\\d{2}\\s+([^,]+)")

    private val houseNo = Regex("\\s+(č\\.\\s?p\\.\\s?)?\\d+[a-zA-Z]?(/\\d+[a-zA-Z]?)?$")

    fun known(lat: Double, lon: Double) = !(lat == 0.0 && lon == 0.0)

    fun coords(lat: Double, lon: Double): String =
        if (known(lat, lon)) String.format(Locale.US, "%.5f, %.5f", lat, lon) else "nezjištěno"

    /** Název místa ze souřadnic (obec, ulice); bez internetu nebo při neúspěchu vrátí souřadnice. */
    fun name(ctx: Context, lat: Double, lon: Double): String {
        if (!known(lat, lon)) return "Poloha nezjištěna"
        try {
            @Suppress("DEPRECATION")
            val list = Geocoder(ctx, Locale("cs", "CZ")).getFromLocation(lat, lon, 5) ?: emptyList()
            var town: String? = null
            for (a in list) {
                for (i in 0..a.maxAddressLineIndex) {
                    val m = afterPsc.find(a.getAddressLine(i) ?: "")
                    if (m != null) { town = m.groupValues[1].trim(); break }
                }
                if (town != null) break
            }
            if (town.isNullOrBlank()) town = list.firstNotNullOfOrNull { it.locality?.takeIf { s -> s.isNotBlank() } }
            if (town.isNullOrBlank()) town = list.firstNotNullOfOrNull { it.subLocality?.takeIf { s -> s.isNotBlank() } }
            if (town.isNullOrBlank()) town = list.firstNotNullOfOrNull { it.subAdminArea?.takeIf { s -> s.isNotBlank() } }
            val first = list.firstOrNull()
            val street = first?.thoroughfare?.takeIf { it.isNotBlank() }
            if (street == null && first != null) {
                // Vesnice bez názvů ulic: adresa začíná místní částí ("Sněhotice 12, 798 07 Brodek u Prostějova").
                // Ta je přesnější než obec, pod kterou místní část spadá.
                var part = first.subLocality?.takeIf { it.isNotBlank() }
                if (part == null) {
                    val seg = (first.getAddressLine(0) ?: "").substringBefore(",").trim()
                    val name = seg.replace(houseNo, "").trim()
                    if (name.isNotEmpty() && name[0].isLetter() && !name.contains("+") &&
                        !name.startsWith("Unnamed", true) && afterPsc.find(seg) == null
                    ) part = name
                }
                if (part != null) return part
            }
            val parts = listOfNotNull(town?.takeIf { it.isNotBlank() }, street)
            if (parts.isNotEmpty()) return parts.joinToString(", ")
        } catch (_: Exception) {}
        return coords(lat, lon)
    }
}
