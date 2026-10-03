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
    val eLon: Double,
    val vehicleId: Long = 1
) {
    val km: Double get() = distM / 1000.0
    val minutes: Long get() = Math.round((endTs - startTs) / 60000.0)
    val avgKmh: Double get() = if (endTs > startTs) distM / ((endTs - startTs) / 1000.0) * 3.6 else 0.0
}

/** Poznámka u jízdy, která vede do cíle a zpět na místo výjezdu. */
const val ROUND_NOTE = "TAM A ZPĚT"

class Reading(val ym: String, val km: Int, val ts: Long)

/** Vozidlo: název, SPZ a stav tachometru před první jízdou zaznamenanou v aplikaci. */
data class Vehicle(val id: Long, val name: String, val plate: String, val odoStart: Int, val home: String = "") {
    val label: String get() = if (plate.isBlank()) name else "$name · $plate"
    val short: String get() = if (plate.isBlank()) name else plate
}

/**
 * Stav tachometru v čase. Vychází z posledního skutečného stavu zadaného uživatelem
 * a přičítá jízdy zaznamenané po něm; bez zadaného stavu z počátečního stavu v nastavení.
 */
class Odo(private val db: Db, private val vehicle: Vehicle) {
    private val vid = vehicle.id
    private val start = vehicle.odoStart
    private val rs = db.readings(vid)

    fun reading(ym: String): Reading? = rs.firstOrNull { it.ym == ym }

    fun state(t: Long, exclude: String? = null): Double? {
        val list = rs.filter { it.ym != exclude }
        val r = list.lastOrNull { it.ts <= t }
        if (r != null) return r.km + db.kmBetween(vid, r.ts, t)
        if (start > 0) return start + db.kmBetween(vid, Long.MIN_VALUE, t)
        val first = list.firstOrNull() ?: return null
        return first.km - db.kmBetween(vid, t, first.ts)
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
    /** Výchozí vozidlo: na něj se zapisují automaticky zaznamenané jízdy a předvyplňuje se ve formulářích. */
    var defaultVehicle: Long
        get() = sp.getLong("defaultVehicle", 1L)
        set(v) = sp.edit().putLong("defaultVehicle", v).apply()
    var stopMin: Int
        get() = sp.getInt("stopMin", 5)
        set(v) = sp.edit().putInt("stopMin", v).apply()
}

class Db(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "kniha.db", null, 9) {
    private val appCtx = ctx.applicationContext

    /** Vozidlo, pro které se zobrazují přehledy; 0 = všechna vozidla. */
    var vehicleFilter: Long = 0L
    private fun vf(): String = if (vehicleFilter == 0L) "" else " AND vehicle_id = $vehicleFilter"


    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE trips(id INTEGER PRIMARY KEY AUTOINCREMENT, start_ts INTEGER, end_ts INTEGER, " +
                "start_lat REAL, start_lon REAL, end_lat REAL, end_lon REAL, start_place TEXT, end_place TEXT, " +
                "dist_m REAL, status TEXT, manual INTEGER, note TEXT, vehicle_id INTEGER DEFAULT 1)"
        )
        db.execSQL(
            "CREATE TABLE fuel(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, station TEXT, " +
                "liters REAL, price_vat REAL, price_novat REAL, photo TEXT, vehicle_id INTEGER DEFAULT 1, address TEXT)"
        )
        createVehicles(db, 0)
        createContacts(db)
    }

    /** Vozidla a skutečné stavy tachometru po vozidlech; založí se první vozidlo, aby aplikace měla kam zapisovat. */
    private fun createVehicles(db: SQLiteDatabase, odoStart: Int) {
        db.execSQL("CREATE TABLE vehicles(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, plate TEXT, odo_start INTEGER, home TEXT)")
        db.execSQL("INSERT INTO vehicles(id, name, plate, odo_start) VALUES(1, 'Moje vozidlo', '', $odoStart)")
        db.execSQL("CREATE TABLE odo2(vehicle_id INTEGER, ym TEXT, km INTEGER, ts INTEGER, PRIMARY KEY(vehicle_id, ym))")
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
            "SELECT id, ts, station, liters, price_vat, price_novat, photo, vehicle_id, address FROM fuel WHERE ts >= ? AND ts < ?" + vf() + " ORDER BY ts DESC, id DESC",
            arrayOf(from.toString(), to.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(
                Fuel(c.getLong(0), c.getLong(1), c.getString(2) ?: "", c.getDouble(3), c.getDouble(4), c.getDouble(5), c.getString(6) ?: "", c.getLong(7), c.getString(8) ?: "")
            )
        }
        return out
    }

    /** Všechny jízdy a účtenky v časovém rozmezí <from, to) – pro grafy. */
    fun tripsRange(from: Long, to: Long): List<Trip> {
        val out = ArrayList<Trip>()
        readableDatabase.rawQuery(
            "SELECT $cols FROM trips WHERE start_ts >= ? AND start_ts < ?" + vf() + " ORDER BY start_ts",
            arrayOf(from.toString(), to.toString())
        ).use { c -> while (c.moveToNext()) out.add(read(c)) }
        return out
    }

    fun fuelRange(from: Long, to: Long): List<Fuel> {
        val out = ArrayList<Fuel>()
        readableDatabase.rawQuery(
            "SELECT id, ts, station, liters, price_vat, price_novat, photo, vehicle_id, address FROM fuel WHERE ts >= ? AND ts < ?" + vf() + " ORDER BY ts",
            arrayOf(from.toString(), to.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(
                Fuel(c.getLong(0), c.getLong(1), c.getString(2) ?: "", c.getDouble(3), c.getDouble(4), c.getDouble(5), c.getString(6) ?: "", c.getLong(7), c.getString(8) ?: "")
            )
        }
        return out
    }

    fun fuelSave(f: Fuel) {
        val v = ContentValues()
        v.put("ts", f.ts); v.put("station", f.station); v.put("liters", f.liters)
        v.put("price_vat", f.priceVat); v.put("price_novat", f.priceNoVat); v.put("photo", f.photo); v.put("vehicle_id", f.vehicleId); v.put("address", f.address)
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
        if (oldVersion < 5) {
            // Dosavadní jízdy, účtenky i stavy tachometru připadnou prvnímu vozidlu.
            val start = appCtx.getSharedPreferences("nastaveni", Context.MODE_PRIVATE).getInt("odoStart", 0)
            createVehicles(db, start)
            db.execSQL("ALTER TABLE trips ADD COLUMN vehicle_id INTEGER DEFAULT 1")
            db.execSQL("ALTER TABLE fuel ADD COLUMN vehicle_id INTEGER DEFAULT 1")
            db.execSQL("INSERT INTO odo2 SELECT 1, ym, km, ts FROM odo")
            db.execSQL("DROP TABLE odo")
        } else if (oldVersion < 6) {
            // výchozí místo vozidla (kde je garážované)
            db.execSQL("ALTER TABLE vehicles ADD COLUMN home TEXT")
        }
        if (oldVersion < 7) createContacts(db)
        else if (oldVersion < 8) {
            db.execSQL("ALTER TABLE contacts ADD COLUMN km_hw REAL")
            db.execSQL("ALTER TABLE contacts ADD COLUMN km_other REAL")
        }
        if (oldVersion < 9) db.execSQL("ALTER TABLE fuel ADD COLUMN address TEXT")
    }

    fun readings(vehicleId: Long): List<Reading> {
        val out = ArrayList<Reading>()
        readableDatabase.rawQuery(
            "SELECT ym, km, ts FROM odo2 WHERE vehicle_id = ? ORDER BY ts", arrayOf(vehicleId.toString())
        ).use { c -> while (c.moveToNext()) out.add(Reading(c.getString(0), c.getInt(1), c.getLong(2))) }
        return out
    }

    fun setReading(vehicleId: Long, ym: String, km: Int, ts: Long) {
        val v = ContentValues(); v.put("vehicle_id", vehicleId); v.put("ym", ym); v.put("km", km); v.put("ts", ts)
        writableDatabase.insertWithOnConflict("odo2", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteReading(vehicleId: Long, ym: String) {
        writableDatabase.delete("odo2", "vehicle_id = ? AND ym = ?", arrayOf(vehicleId.toString(), ym))
    }

    /** Součet km jízd daného vozidla, které začaly v intervalu <from, to). */
    fun kmBetween(vehicleId: Long, from: Long, to: Long): Double {
        readableDatabase.rawQuery(
            "SELECT COALESCE(SUM(dist_m), 0) FROM trips WHERE vehicle_id = ? AND start_ts >= ? AND start_ts < ?",
            arrayOf(vehicleId.toString(), from.toString(), to.toString())
        ).use { c -> return if (c.moveToFirst()) c.getDouble(0) / 1000.0 else 0.0 }
    }

    // ----- adresář cílů -----

    private fun createContacts(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS contacts(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, street TEXT, city TEXT, zip TEXT, km_hw REAL, km_other REAL)")
    }

    fun contacts(): List<Contact> {
        val out = ArrayList<Contact>()
        readableDatabase.rawQuery(// vzestupně podle km po dálnici; firmy bez uvedené vzdálenosti až na konci
            "SELECT id, name, street, city, zip, km_hw, km_other FROM contacts " +
                "ORDER BY (COALESCE(km_hw, 0) <= 0), km_hw, name COLLATE NOCASE", null).use { c ->
            while (c.moveToNext()) out.add(
                Contact(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "", c.getString(4) ?: "", c.getDouble(5), c.getDouble(6))
            )
        }
        return out
    }

    /** Nahradí celý adresář nově nahraným seznamem. */
    fun contactsReplace(list: List<Contact>) {
        val w = writableDatabase
        w.beginTransaction()
        try {
            w.delete("contacts", null, null)
            for (c in list) {
                val v = ContentValues()
                v.put("name", c.name); v.put("street", c.street); v.put("city", c.city); v.put("zip", c.zip); v.put("km_hw", c.kmHighway); v.put("km_other", c.kmOther)
                w.insert("contacts", null, v)
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    fun deleteTrips(ids: Collection<Long>) {
        val w = writableDatabase
        w.beginTransaction()
        try {
            for (id in ids) w.delete("trips", "id = ?", arrayOf(id.toString()))
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    // ----- vozidla -----

    fun vehicles(): List<Vehicle> {
        val out = ArrayList<Vehicle>()
        readableDatabase.rawQuery("SELECT id, name, plate, odo_start, home FROM vehicles ORDER BY id", null).use { c ->
            while (c.moveToNext()) out.add(Vehicle(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getInt(3), c.getString(4) ?: ""))
        }
        return out
    }

    fun vehicle(id: Long): Vehicle? = vehicles().firstOrNull { it.id == id }

    fun vehicleSave(v: Vehicle): Long {
        val cv = ContentValues()
        cv.put("name", v.name); cv.put("plate", v.plate); cv.put("odo_start", v.odoStart); cv.put("home", v.home)
        if (v.id == 0L) return writableDatabase.insert("vehicles", null, cv)
        writableDatabase.update("vehicles", cv, "id = ?", arrayOf(v.id.toString()))
        return v.id
    }

    /** Má vozidlo nějaké jízdy nebo účtenky? Takové nejde smazat. */
    fun vehicleUsed(id: Long): Boolean {
        readableDatabase.rawQuery(
            "SELECT (SELECT COUNT(*) FROM trips WHERE vehicle_id = ?) + (SELECT COUNT(*) FROM fuel WHERE vehicle_id = ?)",
            arrayOf(id.toString(), id.toString())
        ).use { c -> return c.moveToFirst() && c.getInt(0) > 0 }
    }

    fun vehicleDelete(id: Long) {
        writableDatabase.delete("odo2", "vehicle_id = ?", arrayOf(id.toString()))
        writableDatabase.delete("vehicles", "id = ?", arrayOf(id.toString()))
    }

    /** Jsou dvě místa totožná? Rozhodují GPS souřadnice (do 500 m), bez nich shodný název. */
    private fun samePlace(lat1: Double, lon1: Double, name1: String, lat2: Double, lon2: Double, name2: String): Boolean {
        if (Places.known(lat1, lon1) && Places.known(lat2, lon2)) {
            val r = FloatArray(1)
            android.location.Location.distanceBetween(lat1, lon1, lat2, lon2, r)
            return r[0] < 500f
        }
        return name1.trim().equals(name2.trim(), ignoreCase = true)
    }

    /**
     * Sloučit lze jen dvě jízdy téhož vozidla ze stejného dne, které tvoří cestu tam a zpět:
     * start první = cíl druhé a cíl první = start druhé. Vrací důvod, proč to nejde, nebo null.
     */
    fun mergeProblem(ids: Collection<Long>): String? {
        val trips = ids.mapNotNull { get(it) }.sortedBy { it.startTs }
        if (trips.size != 2) return "Sloučit lze právě dvě jízdy."
        val a = trips[0]
        val b = trips[1]
        val ca = Calendar.getInstance(); ca.timeInMillis = a.startTs
        val cb = Calendar.getInstance(); cb.timeInMillis = b.startTs
        val sameDay = ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
        val back = samePlace(a.sLat, a.sLon, a.startPlace, b.eLat, b.eLon, b.endPlace) &&
            samePlace(a.eLat, a.eLon, a.endPlace, b.sLat, b.sLon, b.startPlace)
        if (a.vehicleId != b.vehicleId || !sameDay || !back) return "Tyto dvě jízdy nejdou sloučit."
        return null
    }

    /**
     * Sloučí cestu tam a zpět do jedné jízdy: "od" je výjezd, "do" je cíl první jízdy, km se sečtou,
     * čas je od začátku první do konce druhé a poznámka "TAM A ZPĚT" vysvětluje vyšší km.
     */
    fun mergeTrips(ids: Collection<Long>): Boolean {
        if (mergeProblem(ids) != null) return false
        val trips = ids.mapNotNull { get(it) }.sortedBy { it.startTs }
        val first = trips.first()
        val last = trips.last()
        val notes = listOf(ROUND_NOTE) + trips.map { it.note }.filter { it.isNotBlank() }
        val w = writableDatabase
        w.beginTransaction()
        try {
            val v = ContentValues()
            v.put("end_ts", last.endTs); v.put("dist_m", trips.sumOf { it.distM })
            v.put("note", notes.distinct().joinToString("; "))
            w.update("trips", v, "id = ?", arrayOf(first.id.toString()))
            w.delete("trips", "id = ?", arrayOf(last.id.toString()))
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
        return true
    }

    fun setTripVehicle(id: Long, vehicleId: Long) {
        val v = ContentValues(); v.put("vehicle_id", vehicleId)
        writableDatabase.update("trips", v, "id = ?", arrayOf(id.toString()))
    }

    fun insert(
        startTs: Long, endTs: Long, sLat: Double, sLon: Double, eLat: Double, eLon: Double,
        startPlace: String, endPlace: String, distM: Double, status: String, manual: Boolean, vehicleId: Long
    ): Long {
        val v = ContentValues()
        v.put("start_ts", startTs); v.put("end_ts", endTs)
        v.put("start_lat", sLat); v.put("start_lon", sLon); v.put("end_lat", eLat); v.put("end_lon", eLon)
        v.put("start_place", startPlace); v.put("end_place", endPlace)
        v.put("dist_m", distM); v.put("status", status); v.put("manual", if (manual) 1 else 0); v.put("note", ""); v.put("vehicle_id", vehicleId)
        return writableDatabase.insert("trips", null, v)
    }

    private fun read(c: Cursor) = Trip(
        c.getLong(0), c.getLong(1), c.getLong(2), c.getString(3) ?: "", c.getString(4) ?: "",
        c.getDouble(5), c.getString(6) ?: "N", c.getInt(7) == 1, c.getString(8) ?: "",
        c.getDouble(9), c.getDouble(10), c.getDouble(11), c.getDouble(12), c.getLong(13)
    )

    private val cols = "id, start_ts, end_ts, start_place, end_place, dist_m, status, manual, note, start_lat, start_lon, end_lat, end_lon, vehicle_id"

    /** Jízdy v daném měsíci (month0 = 0..11), nejnovější první. */
    fun month(year: Int, month0: Int): List<Trip> {
        val cal = Calendar.getInstance()
        cal.clear(); cal.set(year, month0, 1, 0, 0, 0)
        val from = cal.timeInMillis
        cal.add(Calendar.MONTH, 1)
        val to = cal.timeInMillis
        val out = ArrayList<Trip>()
        readableDatabase.rawQuery(
            "SELECT $cols FROM trips WHERE start_ts >= ? AND start_ts < ?" + vf() + " ORDER BY start_ts DESC",
            arrayOf(from.toString(), to.toString())
        ).use { c -> while (c.moveToNext()) out.add(read(c)) }
        return out
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
