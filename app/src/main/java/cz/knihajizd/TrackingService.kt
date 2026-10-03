package cz.knihajizd

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import kotlin.concurrent.thread

/**
 * Služba na popředí, která sleduje polohu.
 * Automatický režim: sama pozná začátek a konec jízdy a uloží ji, pokud splní práh rychlosti a délky.
 * Ruční režim: jízdu zahajuje a ukončuje uživatel a uloží se vždy.
 */
class TrackingService : Service(), LocationListener {

    companion object {
        const val ACTION_AUTO = "auto"
        const val ACTION_MANUAL_START = "manual_start"
        const val ACTION_MANUAL_STOP = "manual_stop"
        const val ACTION_STATUS = "status"
        const val ACTION_STOP = "stop"

        private const val IDLE_MS = 10_000L   // interval polohy při čekání na jízdu
        private const val TRIP_MS = 3_000L    // interval polohy během jízdy
        private const val START_SPEED = 4.2f  // m/s (~15 km/h): rychlost, od které se počítá jízda
        private const val MOVE_SPEED = 1.5f   // m/s: pod tím se bere, že vůz stojí

        @Volatile var running = false
        @Volatile var tripActive = false
        @Volatile var tripManual = false
        @Volatile var tripStartTs = 0L
        @Volatile var tripDistM = 0.0
        var onChange: (() -> Unit)? = null

        fun send(ctx: Context, action: String, status: String? = null, vehicleId: Long = 0L) {
            val i = Intent(ctx, TrackingService::class.java).setAction(action)
            if (status != null) i.putExtra("status", status)
            if (vehicleId != 0L) i.putExtra("vehicle", vehicleId)
            ctx.startForegroundService(i)
        }
    }

    private lateinit var lm: LocationManager
    private lateinit var prefs: Prefs
    private lateinit var db: Db
    private val ui = Handler(Looper.getMainLooper())

    private var autoMode = false
    private var candidate = 0
    private var candLoc: Location? = null
    private var candTs = 0L
    private var prev: Location? = null

    private var startLoc: Location? = null
    private var lastLoc: Location? = null
    private var lastMoveLoc: Location? = null
    private var startTs = 0L
    private var lastMoveTs = 0L
    private var dist = 0.0
    private var status = "S"
    private var vehicleId = 1L

    private val tick = object : Runnable {
        override fun run() {
            // Pojistka: když GPS přestane posílat polohu (garáž), jízda se přesto ukončí.
            if (tripActive && !tripManual && System.currentTimeMillis() - lastMoveTs > prefs.stopMin * 60_000L) {
                finishTrip()
                updateNotification()
            }
            ui.postDelayed(this, 30_000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        prefs = Prefs(this)
        db = Db(this)
        val ch = NotificationChannel("zaznam", "Záznam jízd", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        running = true
    }

    override fun onDestroy() {
        if (tripActive) finishTrip()
        running = false
        ui.removeCallbacks(tick)
        try { lm.removeUpdates(this) } catch (_: Exception) {}
        notifyUi()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!goForeground()) { stopSelf(); return START_NOT_STICKY }
        val action = intent?.action ?: if (prefs.mode == "auto") ACTION_AUTO else ACTION_STOP
        when (action) {
            ACTION_AUTO -> {
                autoMode = true
                if (!tripActive) request(IDLE_MS)
            }
            ACTION_MANUAL_START -> if (!tripActive) {
                status = intent?.getStringExtra("status") ?: "S"
                vehicleId = intent?.getLongExtra("vehicle", prefs.defaultVehicle) ?: prefs.defaultVehicle
                begin(true, System.currentTimeMillis(), null)
            }
            ACTION_MANUAL_STOP -> if (tripActive) finishTrip()
            ACTION_STATUS -> status = intent?.getStringExtra("status") ?: status
            ACTION_STOP -> autoMode = false
        }
        if (!autoMode && !tripActive) {
            stopSelf()
            return START_NOT_STICKY
        }
        ui.removeCallbacks(tick)
        ui.postDelayed(tick, 30_000L)
        updateNotification()
        notifyUi()
        return START_STICKY
    }

    private fun goForeground(): Boolean = try {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(1, n)
        true
    } catch (e: Exception) {
        false
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val text = when {
            tripActive && tripManual -> "Probíhá ručně spuštěná jízda"
            tripActive -> "Probíhá jízda"
            else -> "Čekám na jízdu"
        }
        return Notification.Builder(this, "zaznam")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Kniha jízd")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotification() {
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(1, buildNotification())
        } catch (_: Exception) {}
    }

    private fun notifyUi() {
        ui.post { onChange?.invoke() }
    }

    @SuppressLint("MissingPermission")
    private fun request(intervalMs: Long) {
        try {
            lm.removeUpdates(this)
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, intervalMs, 0f, this, Looper.getMainLooper())
        } catch (_: Exception) {
            // chybí oprávnění nebo telefon nemá GPS – záznam nepoběží
        }
    }

    private fun begin(manual: Boolean, ts: Long, loc: Location?) {
        tripActive = true
        tripManual = manual
        startTs = ts
        tripStartTs = ts
        startLoc = loc
        lastLoc = loc
        lastMoveLoc = loc
        lastMoveTs = System.currentTimeMillis()
        dist = 0.0
        tripDistM = 0.0
        candidate = 0
        request(TRIP_MS)
        updateNotification()
        notifyUi()
    }

    override fun onLocationChanged(loc: Location) {
        if (loc.hasAccuracy() && loc.accuracy > 60f) return
        val now = System.currentTimeMillis()
        val p = prev
        val speed = when {
            loc.hasSpeed() -> loc.speed
            p != null && loc.time > p.time -> p.distanceTo(loc) / ((loc.time - p.time) / 1000f)
            else -> 0f
        }
        prev = loc

        if (!tripActive) {
            if (!autoMode) return
            if (speed >= START_SPEED) {
                if (candidate == 0) { candLoc = loc; candTs = now }
                candidate++
                if (candidate >= 3) {
                    status = "S"
                    // automaticky zaznamenaná jízda se zapíše na výchozí vozidlo
                    vehicleId = prefs.defaultVehicle
                    begin(false, candTs, candLoc)
                }
            } else {
                candidate = 0
            }
            return
        }

        val last = lastLoc
        if (startLoc == null || last == null) {
            // první poloha ručně spuštěné jízdy
            startLoc = loc; lastLoc = loc; lastMoveLoc = loc; lastMoveTs = now
            return
        }
        val d = last.distanceTo(loc)
        if (d >= 8f) {
            dist += d
            lastLoc = loc
            tripDistM = dist
        }
        if (speed > MOVE_SPEED) {
            lastMoveTs = now
            lastMoveLoc = loc
        } else if (!tripManual && now - lastMoveTs > prefs.stopMin * 60_000L) {
            finishTrip()
            updateNotification()
        }
    }

    private fun finishTrip() {
        val manual = tripManual
        val endTs = if (manual) System.currentTimeMillis() else lastMoveTs
        val s = startLoc
        val e = if (manual) (lastLoc ?: s) else (lastMoveLoc ?: lastLoc ?: s)
        val d = dist
        val sTs = startTs
        val st = status
        val vid = vehicleId

        tripActive = false
        tripManual = false
        tripDistM = 0.0
        startLoc = null; lastLoc = null; lastMoveLoc = null
        candidate = 0

        val durS = (endTs - sTs) / 1000.0
        val avgKmh = if (durS > 0) d / durS * 3.6 else 0.0
        // Ruční jízda se uloží vždy; automatická jen při splnění prahu rychlosti a minimální délky.
        val keep = manual || (avgKmh >= prefs.threshold && d >= prefs.minKm * 1000.0)
        if (keep) {
            thread {
                val sp = place(s)
                val ep = place(e)
                db.insert(
                    sTs, endTs, s?.latitude ?: 0.0, s?.longitude ?: 0.0, e?.latitude ?: 0.0, e?.longitude ?: 0.0,
                    sp, ep, d, st, manual, vid
                )
                notifyUi()
            }
        }
        if (autoMode) request(IDLE_MS)
        notifyUi()
    }

    private fun place(loc: Location?): String =
        if (loc == null) "Poloha nezjištěna" else Places.name(this, loc.latitude, loc.longitude)

    // Na starších verzích Androidu jsou tyto metody povinné.
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}
