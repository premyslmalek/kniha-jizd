package cz.knihajizd

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Calendar

data class Trip(
    val id: Long,
    val startTs: Long,
    val endTs: Long,
    val startPlace: String,
    val endPlace: String,
    val distM: Double,
    val status: String, // S = služební, P = soukromá, N = nezařazeno
    val manual: Boolean,
    val note: String
) {
    val km: Double get() = distM / 1000.0
    val minutes: Long get() = Math.round((endTs - startTs) / 60000.0)
    val avgKmh: Double get() = if (endTs > startTs) distM / ((endTs - startTs) / 1000.0) * 3.6 else 0.0
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
    var stopMin: Int
        get() = sp.getInt("stopMin", 5)
        set(v) = sp.edit().putInt("stopMin", v).apply()
}

class Db(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "kniha.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE trips(id INTEGER PRIMARY KEY AUTOINCREMENT, start_ts INTEGER, end_ts INTEGER, " +
                "start_lat REAL, start_lon REAL, end_lat REAL, end_lon REAL, start_place TEXT, end_place TEXT, " +
                "dist_m REAL, status TEXT, manual INTEGER, note TEXT)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

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
        c.getDouble(5), c.getString(6) ?: "N", c.getInt(7) == 1, c.getString(8) ?: ""
    )

    private val cols = "id, start_ts, end_ts, start_place, end_place, dist_m, status, manual, note"

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

    fun delete(id: Long) {
        writableDatabase.delete("trips", "id = ?", arrayOf(id.toString()))
    }
}
