package cz.knihajizd

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.text.method.DigitsKeyListener
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private val INK = Color.parseColor("#14201B")
    private val INK2 = Color.parseColor("#20302A")
    private val GROUND = Color.parseColor("#F2F4F1")
    private val LINE = Color.parseColor("#C9CFC8")
    private val MUTED = Color.parseColor("#4A524D")
    private val ON_DARK = Color.parseColor("#A9B8AF")
    private val BLUE = Color.parseColor("#173EA5")
    private val BLUE_BG = Color.parseColor("#DCE6FB")
    private val ORANGE = Color.parseColor("#8A3A06")
    private val ORANGE_BG = Color.parseColor("#FBE3CF")
    private val GREY_BG = Color.parseColor("#E6E8E4")
    private val GREEN = Color.parseColor("#17603F")
    private val RED = Color.parseColor("#A1261B")

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val MONTHS = arrayOf(
        "Leden", "Únor", "Březen", "Duben", "Květen", "Červen",
        "Červenec", "Srpen", "Září", "Říjen", "Listopad", "Prosinec"
    )
    private val cs = Locale("cs", "CZ")
    private val dayFmt = SimpleDateFormat("EEEE d. M.", cs)
    private val dateFmt = SimpleDateFormat("EEEE d. M. yyyy", cs)
    private val timeFmt = SimpleDateFormat("HH:mm", cs)

    private lateinit var db: Db
    private lateinit var prefs: Prefs
    private lateinit var root: FrameLayout
    private val ui = Handler(Looper.getMainLooper())

    private var screen = "list"
    private var detailId = 0L
    private val cal: Calendar = Calendar.getInstance()
    private var manualStatus = "S"
    private var pendingExport = "csv"
    private var fuelDraft = Fuel(0, 0, "", 0.0, 0.0, 0.0)
    private var fuelMsg = ""
    private var cameraUri: Uri? = null

    private var statusView: TextView? = null
    private var noteEdit: EditText? = null
    private var thrEdit: EditText? = null
    private var minEdit: EditText? = null
    private var stopEdit: EditText? = null
    private var odoEdit: EditText? = null

    private val ticker = object : Runnable {
        override fun run() {
            if (screen == "list") statusView?.text = statusText()
            ui.postDelayed(this, 5000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = Db(this)
        prefs = Prefs(this)
        savedInstanceState?.getString("cameraUri")?.let { cameraUri = Uri.parse(it) }
        cal.set(Calendar.DAY_OF_MONTH, 1)
        root = FrameLayout(this)
        setContentView(root)
        render()
        if (!hasLoc()) askPerms() else if (prefs.mode == "auto") TrackingService.send(this, TrackingService.ACTION_AUTO)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        cameraUri?.let { outState.putString("cameraUri", it.toString()) }
    }

    override fun onResume() {
        super.onResume()
        TrackingService.onChange = { if (screen == "list") render() }
        ui.postDelayed(ticker, 5000L)
        if (screen == "list") render()
    }

    override fun onPause() {
        TrackingService.onChange = null
        ui.removeCallbacks(ticker)
        if (screen == "detail") saveNote()
        if (screen == "settings") saveSettings()
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (screen == "list") {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        } else goBack()
    }

    private fun goBack() {
        if (screen == "detail") saveNote()
        if (screen == "settings") saveSettings()
        screen = if (screen == "fuelEdit") "fuel" else "list"
        render()
    }

    // ---------- oprávnění ----------

    private fun hasLoc() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun askPerms() {
        val list = arrayListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(list.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasLoc() && prefs.mode == "auto") TrackingService.send(this, TrackingService.ACTION_AUTO)
        render()
    }

    // ---------- pomocné prvky rozhraní ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(text: String, size: Float = 15f, color: Int = INK, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        return t
    }

    private fun bg(color: Int, radius: Int, stroke: Int? = null): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(radius).toFloat()
        if (stroke != null) g.setStroke(dp(1), stroke)
        return g
    }

    private fun btn(text: String, fill: Int, fg: Int, stroke: Int? = null, onClick: () -> Unit): TextView {
        val t = tv(text, 16f, fg, true)
        t.gravity = Gravity.CENTER
        t.background = bg(fill, 14, stroke)
        t.setPadding(dp(14), dp(14), dp(14), dp(14))
        t.minHeight = dp(48)
        t.isClickable = true
        t.isFocusable = true
        t.setOnClickListener { onClick() }
        return t
    }

    private fun vbox(): LinearLayout {
        val l = LinearLayout(this); l.orientation = LinearLayout.VERTICAL; return l
    }

    private fun hbox(): LinearLayout {
        val l = LinearLayout(this); l.orientation = LinearLayout.HORIZONTAL; return l
    }

    private fun lp(w: Int, h: Int, weight: Float = 0f, top: Int = 0, left: Int = 0): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(w, h, weight)
        p.topMargin = dp(top)
        p.leftMargin = dp(left)
        return p
    }

    private fun card(): LinearLayout {
        val c = vbox()
        c.background = bg(Color.WHITE, 16, LINE)
        c.setPadding(dp(14), dp(14), dp(14), dp(14))
        return c
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun f1(v: Double) = Export.f1(v)

    private fun chip(status: String): TextView {
        val (bgc, fg) = when (status) {
            "S" -> BLUE_BG to BLUE
            "P" -> ORANGE_BG to ORANGE
            else -> GREY_BG to MUTED
        }
        val t = tv(Export.statusName(status), 12f, fg, true)
        t.background = bg(bgc, 10)
        t.setPadding(dp(10), dp(4), dp(10), dp(4))
        return t
    }

    private fun statusBtn(label: String, code: String, current: String, onPick: () -> Unit): TextView {
        val on = code == current
        val fg = if (code == "S") BLUE else ORANGE
        val fill = if (code == "S") BLUE_BG else ORANGE_BG
        return btn(label, if (on) fill else Color.WHITE, if (on) fg else MUTED, if (on) fg else LINE, onPick)
    }

    // ---------- obrazovky ----------

    private fun render() {
        statusView = null; noteEdit = null; thrEdit = null; minEdit = null; stopEdit = null; odoEdit = null
        root.removeAllViews()
        val v = when (screen) {
            "detail" -> detailScreen()
            "settings" -> settingsScreen()
            "fuel" -> fuelScreen()
            "fuelEdit" -> fuelEditScreen()
            else -> listScreen()
        }
        root.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun statusText(): String {
        if (TrackingService.tripActive) {
            return "Jízda probíhá od " + timeFmt.format(Date(TrackingService.tripStartTs)) +
                " · " + f1(TrackingService.tripDistM / 1000.0) + " km"
        }
        if (prefs.mode == "manual") return "Ručně spuštěná jízda se uloží vždy, bez ohledu na rychlost."
        return if (TrackingService.running)
            "Automatický záznam je zapnutý. Uloží se jízdy s průměrnou rychlostí nad ${prefs.threshold} km/h."
        else "Automatický záznam neběží. Otevřete nastavení a zkontrolujte oprávnění."
    }

    private fun km0(v: Long): String = String.format(cs, "%,d", v)

    private fun ym(year: Int, month0: Int) = String.format(Locale.US, "%04d-%02d", year, month0 + 1)

    private fun monthEnd(year: Int, month0: Int): Long =
        if (month0 == 11) db.monthStart(year + 1, 0) else db.monthStart(year, month0 + 1)

    /** Stav tachometru: [na začátku měsíce, na konci měsíce, aktuální stav vozu]. */
    private fun odometer(year: Int, month0: Int): LongArray? {
        val o = Odo(db, prefs.odoStart)
        val a = o.state(db.monthStart(year, month0)) ?: return null
        val b = o.state(monthEnd(year, month0)) ?: return null
        val c = o.state(Long.MAX_VALUE) ?: return null
        return longArrayOf(Math.round(a), Math.round(b), Math.round(c))
    }

    private fun signed(v: Long) = (if (v >= 0) "+" else "−") + km0(Math.abs(v))

    /** Zadání skutečného stavu tachometru pro zobrazený měsíc; rozdíl proti záznamům se dorovná. */
    private fun readingDialog(year: Int, month0: Int) {
        val now = System.currentTimeMillis()
        val to = monthEnd(year, month0)
        if (db.monthStart(year, month0) > now) { toast("Tento měsíc ještě nezačal."); return }
        val key = ym(year, month0)
        val existing = Odo(db, prefs.odoStart).reading(key)
        val e = numberField(existing?.km ?: 0)
        if (existing == null) e.setText("")
        e.hint = "stav tachometru v km"
        val wrap = FrameLayout(this)
        wrap.setPadding(dp(20), dp(8), dp(20), 0)
        wrap.addView(e)
        val b = AlertDialog.Builder(this)
            .setTitle("Skutečný stav tachometru – " + MONTHS[month0] + " " + year)
            .setMessage(
                if (to <= now) "Zadejte stav tachometru na konci měsíce."
                else "Zadejte aktuální stav tachometru. Další jízdy v tomto měsíci se k němu přičtou."
            )
            .setView(wrap)
            .setPositiveButton("Uložit") { _, _ ->
                val km = e.text.toString().trim().toIntOrNull()
                if (km != null && km > 0) { db.setReading(key, km, minOf(now, to)); render() }
                else toast("Stav nebyl uložen – zadejte číslo.")
            }
            .setNegativeButton("Zrušit", null)
        if (existing != null) b.setNeutralButton("Smazat") { _, _ -> db.deleteReading(key); render() }
        b.show()
    }

    private fun listScreen(): View {
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH)
        val trips = db.month(year, month)
        val page = vbox()
        page.setBackgroundColor(GROUND)

        val content = vbox()
        val top = vbox()
        top.setBackgroundColor(INK)
        top.setPadding(dp(16), dp(20), dp(16), dp(8))
        val head = vbox()
        head.setBackgroundColor(INK)
        head.setPadding(dp(16), dp(4), dp(16), dp(16))
        val nav = hbox()
        nav.gravity = Gravity.CENTER_VERTICAL
        nav.addView(btn("‹", INK2, Color.WHITE) { cal.add(Calendar.MONTH, -1); render() }, lp(dp(56), WRAP))
        val title = tv(MONTHS[month] + " " + year, 24f, Color.WHITE, true)
        title.gravity = Gravity.CENTER
        nav.addView(title, lp(0, WRAP, 1f))
        nav.addView(btn("›", INK2, Color.WHITE) { cal.add(Calendar.MONTH, 1); render() }, lp(dp(56), WRAP))
        top.addView(nav)
        page.addView(top)
        val km = trips.sumOf { it.km }
        val kmS = trips.filter { it.status == "S" }.sumOf { it.km }
        val stats = tv("Jízd: ${trips.size}  ·  Celkem ${f1(km)} km  ·  Služebně ${f1(kmS)} km", 14f, ON_DARK)
        stats.gravity = Gravity.CENTER
        head.addView(stats, lp(MATCH, WRAP, 0f, 4))
        val odo = odometer(year, month)
        val odoText = if (odo == null) "Počáteční stav tachometru zadáte v nastavení."
        else "Tachometr v měsíci: ${km0(odo[0])} → ${km0(odo[1])} km\nAktuální stav vozu: ${km0(odo[2])} km"
        val odoView = tv(odoText, 14f, if (odo == null) ON_DARK else Color.WHITE, odo != null)
        odoView.gravity = Gravity.CENTER
        head.addView(odoView, lp(MATCH, WRAP, 0f, 8))
        val odoCalc = Odo(db, prefs.odoStart)
        val reading = odoCalc.reading(ym(year, month))
        if (reading != null) {
            val adj = odoCalc.adjustment(ym(year, month))
            val line = "Skutečný stav zadán: ${km0(reading.km.toLong())} km" +
                (if (adj != null) " (dorovnání ${signed(adj)} km)" else "")
            val rv = tv(line, 13f, ON_DARK)
            rv.gravity = Gravity.CENTER
            head.addView(rv, lp(MATCH, WRAP, 0f, 4))
        }
        val rb = tv(if (reading == null) "Zadat skutečný stav tachometru" else "Upravit skutečný stav tachometru", 14f, Color.WHITE, true)
        rb.gravity = Gravity.CENTER
        rb.background = bg(INK2, 12, MUTED)
        rb.setPadding(dp(12), dp(12), dp(12), dp(12))
        rb.isClickable = true
        rb.setOnClickListener { readingDialog(year, month) }
        head.addView(rb, lp(MATCH, WRAP, 0f, 10))
        val fuelView = tv(fuelSummary(db.fuelMonth(year, month)), 14f, Color.WHITE, true)
        fuelView.gravity = Gravity.CENTER
        head.addView(fuelView, lp(MATCH, WRAP, 0f, 10))
        content.addView(head)

        val ctl = card()
        if (!hasLoc()) {
            ctl.addView(tv("Aplikace nemá povolený přístup k poloze. Bez něj nelze jízdy zaznamenávat.", 14f))
            ctl.addView(btn("Povolit polohu", BLUE, Color.WHITE) { askPerms() }, lp(MATCH, WRAP, 0f, 10))
        } else if (prefs.mode == "manual") {
            val active = TrackingService.tripActive
            val row = hbox()
            val pick = { code: String ->
                manualStatus = code
                if (TrackingService.tripActive) TrackingService.send(this, TrackingService.ACTION_STATUS, code)
                render()
            }
            row.addView(statusBtn("Služební", "S", manualStatus) { pick("S") }, lp(0, WRAP, 1f))
            row.addView(statusBtn("Soukromá", "P", manualStatus) { pick("P") }, lp(0, WRAP, 1f, 0, 8))
            ctl.addView(row)
            ctl.addView(
                btn(if (active) "Ukončit jízdu" else "Zahájit jízdu", if (active) RED else GREEN, Color.WHITE) {
                    if (TrackingService.tripActive) TrackingService.send(this, TrackingService.ACTION_MANUAL_STOP)
                    else TrackingService.send(this, TrackingService.ACTION_MANUAL_START, manualStatus)
                }, lp(MATCH, WRAP, 0f, 10)
            )
            val sv = tv(statusText(), 13f, MUTED)
            sv.gravity = Gravity.CENTER
            statusView = sv
            ctl.addView(sv, lp(MATCH, WRAP, 0f, 8))
        } else {
            val sv = tv(statusText(), 14f, INK)
            statusView = sv
            ctl.addView(sv)
        }
        val ctlLp = lp(MATCH, WRAP, 0f, 12)
        ctlLp.leftMargin = dp(16); ctlLp.rightMargin = dp(16)
        content.addView(ctl, ctlLp)

        val list = vbox()
        list.setPadding(dp(16), dp(4), dp(16), dp(16))
        if (trips.isEmpty()) {
            val e = tv("V tomto měsíci zatím není žádná jízda.", 15f, MUTED)
            e.gravity = Gravity.CENTER
            list.addView(e, lp(MATCH, WRAP, 0f, 40))
        }
        var lastDay = ""
        for (t in trips) {
            val day = dayFmt.format(Date(t.startTs)).replaceFirstChar { it.uppercase() }
            if (day != lastDay) {
                list.addView(tv(day, 14f, INK, true), lp(MATCH, WRAP, 0f, 14))
                lastDay = day
            }
            list.addView(tripCard(t), lp(MATCH, WRAP, 0f, 8))
        }
        val scroll = ScrollView(this)
        content.addView(list)
        scroll.addView(content)
        page.addView(scroll, lp(MATCH, 0, 1f))

        val bottom = hbox()
        bottom.setBackgroundColor(Color.WHITE)
        bottom.setPadding(dp(16), dp(10), dp(16), dp(10))
        bottom.setPadding(dp(12), dp(10), dp(12), dp(10))
        bottom.addView(btn("Účtenky", Color.WHITE, INK, LINE) { screen = "fuel"; render() }, lp(0, WRAP, 1f))
        bottom.addView(btn("Export", BLUE, Color.WHITE) { exportDialog() }, lp(0, WRAP, 1f, 0, 8))
        bottom.addView(btn("Nastavení", Color.WHITE, INK, LINE) { screen = "settings"; render() }, lp(0, WRAP, 1f, 0, 8))
        page.addView(bottom)
        return page
    }

    private fun tripCard(t: Trip): View {
        val c = card()
        val top = hbox()
        top.gravity = Gravity.CENTER_VERTICAL
        top.addView(
            tv(timeFmt.format(Date(t.startTs)) + " – " + timeFmt.format(Date(t.endTs)), 13f, MUTED),
            lp(0, WRAP, 1f)
        )
        top.addView(chip(t.status))
        c.addView(top)
        c.addView(tv(t.startPlace, 15f, INK, true), lp(MATCH, WRAP, 0f, 8))
        c.addView(tv("→ " + t.endPlace, 15f, INK, true), lp(MATCH, WRAP, 0f, 2))
        val meta = f1(t.km) + " km  ·  Ø " + Math.round(t.avgKmh) + " km/h  ·  " + t.minutes + " min" +
            (if (t.manual) "  ·  ručně" else "")
        c.addView(tv(meta, 13f, MUTED), lp(MATCH, WRAP, 0f, 8))
        c.isClickable = true
        c.setOnClickListener { detailId = t.id; screen = "detail"; render() }
        return c
    }

    private fun topBar(title: String): View {
        val bar = hbox()
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.addView(btn("‹ Zpět", Color.WHITE, INK, LINE) { goBack() })
        bar.addView(tv(title, 20f, INK, true), lp(0, WRAP, 1f, 0, 12))
        return bar
    }

    /** Řádek s přesnými souřadnicemi; klepnutím se místo otevře v mapě. */
    private fun gpsLine(lat: Double, lon: Double, label: String): TextView {
        val known = Places.known(lat, lon)
        val t = tv("GPS: " + Places.coords(lat, lon) + if (known) "  ·  zobrazit na mapě" else "", 13f, if (known) BLUE else MUTED)
        t.setPadding(0, dp(6), 0, dp(6))
        if (known) {
            t.isClickable = true
            t.setOnClickListener {
                try {
                    val q = Places.coords(lat, lon).replace(" ", "")
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$q?q=$q(" + Uri.encode(label) + ")")))
                } catch (e: Exception) {
                    toast("V telefonu není aplikace s mapou.")
                }
            }
        }
        return t
    }

    /** Název startu nebo cíle; klepnutím ho lze ručně přepsat. */
    private fun placeName(t: Trip, start: Boolean): TextView {
        val v = tv(if (start) t.startPlace else t.endPlace, 16f, INK, true)
        v.setPadding(0, dp(4), 0, dp(4))
        v.isClickable = true
        v.setOnClickListener {
            val e = EditText(this)
            e.setText(if (start) t.startPlace else t.endPlace)
            e.setSelection(e.text.length)
            val wrap = FrameLayout(this)
            wrap.setPadding(dp(20), dp(8), dp(20), 0)
            wrap.addView(e)
            AlertDialog.Builder(this)
                .setTitle(if (start) "Název místa startu" else "Název místa cíle")
                .setView(wrap)
                .setPositiveButton("Uložit") { _, _ ->
                    val name = e.text.toString().trim()
                    if (name.isNotEmpty()) {
                        saveNote()
                        if (start) db.setPlaces(t.id, name, t.endPlace) else db.setPlaces(t.id, t.startPlace, name)
                        render()
                    }
                }
                .setNegativeButton("Zrušit", null)
                .show()
        }
        return v
    }

    private fun saveNote() {
        val e = noteEdit ?: return
        db.setNote(detailId, e.text.toString().trim())
    }

    private fun detailScreen(): View {
        val t = db.get(detailId)
        val page = vbox()
        page.setBackgroundColor(GROUND)
        page.setPadding(dp(16), dp(16), dp(16), dp(16))
        if (t == null) {
            page.addView(topBar("Jízda"))
            page.addView(tv("Jízda už neexistuje.", 15f, MUTED), lp(MATCH, WRAP, 0f, 20))
            return page
        }
        page.addView(topBar(dateFmt.format(Date(t.startTs)).replaceFirstChar { it.uppercase() }))

        val body = vbox()
        val places = card()
        places.addView(tv("Start · " + timeFmt.format(Date(t.startTs)), 12f, MUTED))
        places.addView(placeName(t, true))
        places.addView(gpsLine(t.sLat, t.sLon, "Start"))
        places.addView(tv("Cíl · " + timeFmt.format(Date(t.endTs)), 12f, MUTED), lp(MATCH, WRAP, 0f, 12))
        places.addView(placeName(t, false))
        places.addView(gpsLine(t.eLat, t.eLon, "Cíl"))
        places.addView(tv("Klepnutím na název místa ho můžete přepsat.", 12f, MUTED))
        if (Places.known(t.sLat, t.sLon) || Places.known(t.eLat, t.eLon)) {
            places.addView(
                btn("Načíst názvy míst znovu", Color.WHITE, INK, LINE) {
                    saveNote()
                    toast("Načítám názvy míst…")
                    Thread {
                        val sp = Places.name(this, t.sLat, t.sLon)
                        val ep = Places.name(this, t.eLat, t.eLon)
                        db.setPlaces(t.id, sp, ep)
                        ui.post { if (screen == "detail" && detailId == t.id) render() }
                    }.start()
                }, lp(MATCH, WRAP, 0f, 12)
            )
        }
        body.addView(places, lp(MATCH, WRAP, 0f, 14))

        val stats = card()
        stats.addView(tv("Vzdálenost: " + f1(t.km) + " km", 15f))
        stats.addView(tv("Doba: " + t.minutes + " min", 15f), lp(MATCH, WRAP, 0f, 4))
        stats.addView(tv("Průměrná rychlost: " + Math.round(t.avgKmh) + " km/h", 15f), lp(MATCH, WRAP, 0f, 4))
        stats.addView(
            tv(if (t.manual) "Zaznamenáno ručně" else "Zaznamenáno automaticky", 13f, MUTED),
            lp(MATCH, WRAP, 0f, 6)
        )
        body.addView(stats, lp(MATCH, WRAP, 0f, 10))

        body.addView(tv("Status jízdy", 14f, INK, true), lp(MATCH, WRAP, 0f, 16))
        val row = hbox()
        val pick = { code: String -> saveNote(); db.setStatus(t.id, code); render() }
        row.addView(statusBtn("Služební", "S", t.status) { pick("S") }, lp(0, WRAP, 1f))
        row.addView(statusBtn("Soukromá", "P", t.status) { pick("P") }, lp(0, WRAP, 1f, 0, 8))
        body.addView(row, lp(MATCH, WRAP, 0f, 8))

        body.addView(tv("Poznámka", 14f, INK, true), lp(MATCH, WRAP, 0f, 16))
        val note = EditText(this)
        note.setText(t.note)
        note.hint = "Např. účel cesty, zákazník"
        note.textSize = 15f
        note.background = bg(Color.WHITE, 12, LINE)
        note.setPadding(dp(12), dp(12), dp(12), dp(12))
        noteEdit = note
        body.addView(note, lp(MATCH, WRAP, 0f, 6))

        body.addView(
            btn("Vymazat jízdu", Color.WHITE, RED, LINE) {
                AlertDialog.Builder(this)
                    .setTitle("Vymazat jízdu?")
                    .setMessage("Akci nelze vrátit.")
                    .setPositiveButton("Vymazat") { _, _ ->
                        db.delete(t.id); noteEdit = null; screen = "list"; render()
                    }
                    .setNegativeButton("Ponechat", null)
                    .show()
            }, lp(MATCH, WRAP, 0f, 20)
        )
        val scroll = ScrollView(this)
        scroll.addView(body)
        page.addView(scroll, lp(MATCH, 0, 1f))
        return page
    }

    private fun numberField(value: Int): EditText {
        val e = EditText(this)
        e.inputType = InputType.TYPE_CLASS_NUMBER
        e.setText(value.toString())
        e.textSize = 16f
        e.gravity = Gravity.CENTER
        e.background = bg(Color.WHITE, 12, LINE)
        e.setPadding(dp(8), dp(10), dp(8), dp(10))
        return e
    }

    private fun settingRow(label: String, hint: String, field: EditText, unit: String): View {
        val row = hbox()
        row.gravity = Gravity.CENTER_VERTICAL
        val texts = vbox()
        texts.addView(tv(label, 15f, INK, true))
        texts.addView(tv(hint, 13f, MUTED))
        row.addView(texts, lp(0, WRAP, 1f))
        row.addView(field, lp(dp(64), WRAP, 0f, 0, 8))
        row.addView(tv(unit, 14f, MUTED), lp(dp(48), WRAP, 0f, 0, 6))
        return row
    }

    private fun saveSettings() {
        thrEdit?.text?.toString()?.toIntOrNull()?.let { prefs.threshold = it.coerceIn(0, 200) }
        minEdit?.text?.toString()?.toIntOrNull()?.let { prefs.minKm = it.coerceIn(0, 100) }
        stopEdit?.text?.toString()?.toIntOrNull()?.let { prefs.stopMin = it.coerceIn(1, 60) }
        odoEdit?.let { prefs.odoStart = (it.text.toString().trim().toIntOrNull() ?: 0).coerceIn(0, 9_999_999) }
    }

    private fun setMode(m: String) {
        saveSettings()
        prefs.mode = m
        if (m == "auto") {
            if (hasLoc()) TrackingService.send(this, TrackingService.ACTION_AUTO) else askPerms()
        } else if (TrackingService.running) {
            TrackingService.send(this, TrackingService.ACTION_STOP)
        }
        render()
    }

    private fun modeBtn(label: String, desc: String, code: String): View {
        val on = prefs.mode == code
        val b = vbox()
        b.background = bg(if (on) INK else Color.WHITE, 16, if (on) INK else LINE)
        b.setPadding(dp(16), dp(14), dp(16), dp(14))
        b.addView(tv(label, 16f, if (on) Color.WHITE else INK, true))
        b.addView(tv(desc, 13f, if (on) ON_DARK else MUTED))
        b.isClickable = true
        b.setOnClickListener { setMode(code) }
        return b
    }

    private fun settingsScreen(): View {
        val page = vbox()
        page.setBackgroundColor(GROUND)
        page.setPadding(dp(16), dp(16), dp(16), dp(16))
        page.addView(topBar("Nastavení"))
        val body = vbox()

        body.addView(tv("Způsob záznamu", 14f, INK, true), lp(MATCH, WRAP, 0f, 16))
        body.addView(
            modeBtn("Trvale na pozadí", "Aplikace běží stále a jízdy rozpozná a uloží sama.", "auto"),
            lp(MATCH, WRAP, 0f, 8)
        )
        body.addView(
            modeBtn("Ručně tlačítkem", "Jízdu zahájíte a ukončíte na úvodní obrazovce. Uloží se každá jízda.", "manual"),
            lp(MATCH, WRAP, 0f, 8)
        )

        body.addView(tv("Pravidla automatického záznamu", 14f, INK, true), lp(MATCH, WRAP, 0f, 18))
        val rules = card()
        val thr = numberField(prefs.threshold); thrEdit = thr
        val min = numberField(prefs.minKm); minEdit = min
        val stop = numberField(prefs.stopMin); stopEdit = stop
        rules.addView(settingRow("Průměrná rychlost nad", "Pomalejší jízdy se neuloží", thr, "km/h"))
        rules.addView(settingRow("Minimální délka jízdy", "Kratší přejezdy se neuloží", min, "km"), lp(MATCH, WRAP, 0f, 12))
        rules.addView(settingRow("Konec jízdy po stání", "Kratší zastávka jízdu nerozdělí", stop, "min"), lp(MATCH, WRAP, 0f, 12))
        body.addView(rules, lp(MATCH, WRAP, 0f, 8))
        body.addView(tv("Pro ručně spuštěné jízdy se tato pravidla nepoužijí.", 13f, MUTED), lp(MATCH, WRAP, 0f, 6))

        body.addView(tv("Vozidlo", 14f, INK, true), lp(MATCH, WRAP, 0f, 18))
        val car = card()
        car.addView(tv("Počáteční stav tachometru (km)", 15f, INK, true))
        car.addView(tv("Stav před první jízdou zaznamenanou v aplikaci. K němu se přičítají všechny uložené jízdy.", 13f, MUTED))
        val odoField = numberField(prefs.odoStart)
        if (prefs.odoStart == 0) odoField.setText("")
        odoField.hint = "např. 84500"
        odoEdit = odoField
        car.addView(odoField, lp(MATCH, WRAP, 0f, 10))
        body.addView(car, lp(MATCH, WRAP, 0f, 8))

        body.addView(tv("Oprávnění", 14f, INK, true), lp(MATCH, WRAP, 0f, 18))
        val perm = card()
        perm.addView(tv("Poloha: " + if (hasLoc()) "povolena" else "nepovolena", 15f))
        if (!hasLoc()) perm.addView(btn("Povolit polohu", BLUE, Color.WHITE) { askPerms() }, lp(MATCH, WRAP, 0f, 10))
        perm.addView(
            tv("Aby systém záznam na pozadí neukončoval, vypněte pro Knihu jízd úsporu baterie.", 13f, MUTED),
            lp(MATCH, WRAP, 0f, 10)
        )
        perm.addView(
            btn("Otevřít nastavení úspory baterie", Color.WHITE, INK, LINE) {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (e: Exception) {
                    toast("Nastavení úspory baterie se nepodařilo otevřít.")
                }
            }, lp(MATCH, WRAP, 0f, 10)
        )
        body.addView(perm, lp(MATCH, WRAP, 0f, 8))

        val scroll = ScrollView(this)
        scroll.addView(body)
        page.addView(scroll, lp(MATCH, 0, 1f))
        return page
    }

    // ---------- účtenky za tankování ----------

    private val dmy = SimpleDateFormat("d.M.yyyy", cs)

    private fun money(v: Double): String = String.format(cs, "%,.2f", v)

    private fun fuelSummary(list: List<Fuel>): String =
        "Tankování v měsíci: " + String.format(cs, "%.2f", list.sumOf { it.liters }) + " l  ·  " +
            money(list.sumOf { it.priceVat }) + " Kč vč. DPH"

    private fun fuelScreen(): View {
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH)
        val items = db.fuelMonth(year, month)
        val page = vbox()
        page.setBackgroundColor(GROUND)
        page.setPadding(dp(16), dp(16), dp(16), dp(16))
        page.addView(topBar("Účtenky – " + MONTHS[month] + " " + year))

        val body = vbox()
        val sum = card()
        sum.addView(tv(fuelSummary(items), 15f, INK, true))
        sum.addView(
            tv("Bez DPH: " + money(items.sumOf { it.priceNoVat }) + " Kč  ·  účtenek: " + items.size, 13f, MUTED),
            lp(MATCH, WRAP, 0f, 4)
        )
        body.addView(sum, lp(MATCH, WRAP, 0f, 14))
        body.addView(btn("Zadat účtenku", GREEN, Color.WHITE) { newReceiptDialog() }, lp(MATCH, WRAP, 0f, 10))

        if (items.isEmpty()) {
            val e = tv("V tomto měsíci zatím není žádná účtenka.", 15f, MUTED)
            e.gravity = Gravity.CENTER
            body.addView(e, lp(MATCH, WRAP, 0f, 30))
        }
        for (f in items) {
            val c = card()
            val top = hbox()
            top.addView(tv(f.station.ifBlank { "Neuvedeno" }, 16f, INK, true), lp(0, WRAP, 1f))
            top.addView(tv(dmy.format(Date(f.ts)), 13f, MUTED))
            c.addView(top)
            c.addView(
                tv(String.format(cs, "%.2f", f.liters) + " l  ·  " + money(f.priceVat) + " Kč vč. DPH", 15f),
                lp(MATCH, WRAP, 0f, 6)
            )
            c.addView(tv(money(f.priceNoVat) + " Kč bez DPH", 13f, MUTED), lp(MATCH, WRAP, 0f, 2))
            c.isClickable = true
            c.setOnClickListener { fuelDraft = f; fuelMsg = ""; screen = "fuelEdit"; render() }
            body.addView(c, lp(MATCH, WRAP, 0f, 8))
        }
        val scroll = ScrollView(this)
        scroll.addView(body)
        page.addView(scroll, lp(MATCH, 0, 1f))
        return page
    }

    private fun newReceiptDialog() {
        AlertDialog.Builder(this)
            .setTitle("Nová účtenka")
            .setItems(arrayOf("Vyfotit účtenku", "Vybrat fotku z galerie", "Zadat ručně")) { _, which ->
                when (which) {
                    0 -> takePhoto()
                    1 -> pickImage()
                    else -> openFuelForm(Fuel(0, System.currentTimeMillis(), "", 0.0, 0.0, 0.0), "")
                }
            }
            .show()
    }

    private fun pickImage() {
        val i = Intent(Intent.ACTION_GET_CONTENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "image/*"
        try {
            startActivityForResult(i, 8)
        } catch (e: Exception) {
            toast("V telefonu chybí aplikace pro výběr obrázků.")
        }
    }

    private fun takePhoto() {
        // Na starším Androidu by focení do galerie vyžadovalo další oprávnění, proto se nabídne výběr fotky.
        if (Build.VERSION.SDK_INT < 29) { pickImage(); return }
        try {
            val v = ContentValues()
            v.put(MediaStore.Images.Media.DISPLAY_NAME, "uctenka_" + System.currentTimeMillis() + ".jpg")
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)
            if (uri == null) { pickImage(); return }
            cameraUri = uri
            val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            i.putExtra(MediaStore.EXTRA_OUTPUT, uri)
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            startActivityForResult(i, 9)
        } catch (e: Exception) {
            toast("Fotoaparát se nepodařilo otevřít. Vyberte fotku z galerie.")
        }
    }

    /** Přečte text z fotky účtenky a otevře formulář s předvyplněnými údaji ke kontrole. */
    private fun readReceipt(uri: Uri) {
        val empty = Fuel(0, System.currentTimeMillis(), "", 0.0, 0.0, 0.0)
        val failed = "Z fotky se nepodařilo nic přečíst. Vyplňte údaje ručně."
        toast("Čtu účtenku…")
        try {
            val image = InputImage.fromFilePath(this, uri)
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(image)
                .addOnSuccessListener { res ->
                    if (res.text.isBlank()) openFuelForm(empty, failed)
                    else openFuelForm(
                        Receipts.parse(res.text),
                        "Údaje jsou přečtené z fotky a mohou být chybné. Zkontrolujte je a opravte před uložením."
                    )
                }
                .addOnFailureListener { openFuelForm(empty, failed) }
        } catch (e: Exception) {
            openFuelForm(empty, failed)
        }
    }

    private fun openFuelForm(f: Fuel, msg: String) {
        fuelDraft = f
        fuelMsg = msg
        screen = "fuelEdit"
        render()
    }

    private fun field(value: String, hint: String, digits: String?): EditText {
        val e = EditText(this)
        e.setText(value)
        e.hint = hint
        e.textSize = 16f
        e.setSingleLine(true)
        e.background = bg(Color.WHITE, 12, LINE)
        e.setPadding(dp(12), dp(12), dp(12), dp(12))
        if (digits != null) e.keyListener = DigitsKeyListener.getInstance(digits)
        return e
    }

    private fun num(e: EditText): Double =
        e.text.toString().trim().replace(" ", "").replace(',', '.').toDoubleOrNull() ?: 0.0

    private fun dec(v: Double): String = if (v > 0.0) String.format(cs, "%.2f", v) else ""

    private fun fuelEditScreen(): View {
        val f = fuelDraft
        val page = vbox()
        page.setBackgroundColor(GROUND)
        page.setPadding(dp(16), dp(16), dp(16), dp(16))
        page.addView(topBar(if (f.id == 0L) "Nová účtenka" else "Účtenka"))

        val body = vbox()
        if (fuelMsg.isNotEmpty()) {
            val m = tv(fuelMsg, 14f, ORANGE, true)
            m.background = bg(ORANGE_BG, 12)
            m.setPadding(dp(12), dp(10), dp(12), dp(10))
            body.addView(m, lp(MATCH, WRAP, 0f, 14))
        }
        val date = field(dmy.format(Date(if (f.ts > 0) f.ts else System.currentTimeMillis())), "např. 3.10.2026", "0123456789.")
        val station = field(f.station, "např. MOL Prostějov", null)
        val liters = field(dec(f.liters), "např. 42,15", "0123456789,.")
        val vat = field(dec(f.priceVat), "např. 1560,00", "0123456789,.")
        val noVat = field(dec(f.priceNoVat), "např. 1289,26", "0123456789,.")

        body.addView(tv("Datum", 14f, INK, true), lp(MATCH, WRAP, 0f, 14))
        body.addView(date, lp(MATCH, WRAP, 0f, 6))
        body.addView(tv("Čerpací stanice", 14f, INK, true), lp(MATCH, WRAP, 0f, 12))
        body.addView(station, lp(MATCH, WRAP, 0f, 6))
        body.addView(tv("Počet litrů", 14f, INK, true), lp(MATCH, WRAP, 0f, 12))
        body.addView(liters, lp(MATCH, WRAP, 0f, 6))
        body.addView(tv("Cena vč. DPH (Kč)", 14f, INK, true), lp(MATCH, WRAP, 0f, 12))
        body.addView(vat, lp(MATCH, WRAP, 0f, 6))
        body.addView(tv("Cena bez DPH (Kč)", 14f, INK, true), lp(MATCH, WRAP, 0f, 12))
        body.addView(noVat, lp(MATCH, WRAP, 0f, 6))
        body.addView(
            btn("Dopočítat cenu bez DPH (21 %)", Color.WHITE, INK, LINE) {
                val v = num(vat)
                if (v > 0.0) noVat.setText(dec(Math.round(v / 1.21 * 100.0) / 100.0))
                else toast("Nejdřív zadejte cenu vč. DPH.")
            }, lp(MATCH, WRAP, 0f, 8)
        )

        body.addView(
            btn("Uložit účtenku", GREEN, Color.WHITE) {
                val fmt = SimpleDateFormat("d.M.yyyy", cs)
                fmt.isLenient = false
                val d = try { fmt.parse(date.text.toString().trim().replace(" ", "")) } catch (e: Exception) { null }
                val l = num(liters)
                val pv = num(vat)
                if (d == null) toast("Datum zadejte ve tvaru 3.10.2026.")
                else if (l <= 0.0 || pv <= 0.0) toast("Vyplňte počet litrů a cenu vč. DPH.")
                else {
                    val c = Calendar.getInstance()
                    c.time = d
                    c.set(Calendar.HOUR_OF_DAY, 12)
                    db.fuelSave(Fuel(f.id, c.timeInMillis, station.text.toString().trim(), l, pv, num(noVat)))
                    // přehled účtenek se přepne na měsíc uložené účtenky
                    cal.set(Calendar.YEAR, c.get(Calendar.YEAR))
                    cal.set(Calendar.MONTH, c.get(Calendar.MONTH))
                    screen = "fuel"
                    render()
                }
            }, lp(MATCH, WRAP, 0f, 18)
        )
        if (f.id != 0L) {
            body.addView(
                btn("Vymazat účtenku", Color.WHITE, RED, LINE) {
                    AlertDialog.Builder(this)
                        .setTitle("Vymazat účtenku?")
                        .setMessage("Akci nelze vrátit.")
                        .setPositiveButton("Vymazat") { _, _ -> db.fuelDelete(f.id); screen = "fuel"; render() }
                        .setNegativeButton("Ponechat", null)
                        .show()
                }, lp(MATCH, WRAP, 0f, 10)
            )
        }
        val scroll = ScrollView(this)
        scroll.addView(body)
        page.addView(scroll, lp(MATCH, 0, 1f))
        return page
    }

    // ---------- export ----------

    private fun exportDialog() {
        val title = "Export – " + MONTHS[cal.get(Calendar.MONTH)] + " " + cal.get(Calendar.YEAR)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(arrayOf("Excel (soubor CSV)", "PDF")) { _, which -> startExport(if (which == 0) "csv" else "pdf") }
            .show()
    }

    private fun startExport(kind: String) {
        pendingExport = kind
        val name = String.format(Locale.US, "Kniha-jizd-%04d-%02d.%s", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, kind)
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = if (kind == "csv") "text/csv" else "application/pdf"
        i.putExtra(Intent.EXTRA_TITLE, name)
        try {
            startActivityForResult(i, 7)
        } catch (e: Exception) {
            toast("V telefonu chybí aplikace pro ukládání souborů.")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 8 || requestCode == 9) {
            val uri = if (requestCode == 9) cameraUri else data?.data
            if (resultCode == RESULT_OK && uri != null) readReceipt(uri)
            else if (requestCode == 9 && uri != null) {
                // focení zrušeno – prázdný záznam v galerii se odstraní
                try { contentResolver.delete(uri, null, null) } catch (_: Exception) {}
            }
            if (requestCode == 9) cameraUri = null
            return
        }
        if (requestCode != 7 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH)
        val trips = db.month(year, month)
        try {
            val out = contentResolver.openOutputStream(uri)
            if (out == null) { toast("Soubor se nepodařilo uložit."); return }
            out.use {
                if (pendingExport == "csv") Export.csv(it, trips)
                else {
                    val o = odometer(year, month)
                    val odoTxt = if (o == null) null
                    else {
                        val adj = Odo(db, prefs.odoStart).adjustment(ym(year, month))
                        "Tachometr: ${km0(o[0])} → ${km0(o[1])} km" +
                            (if (adj != null) " (dorovnání ${signed(adj)} km)" else "")
                    }
                    val extra = listOfNotNull(odoTxt, fuelSummary(db.fuelMonth(year, month))).joinToString("   ")
                    Export.pdf(it, "Kniha jízd – " + MONTHS[month] + " " + year, trips, extra)
                }
            }
            toast("Soubor je uložen.")
        } catch (e: Exception) {
            toast("Soubor se nepodařilo uložit.")
        }
    }
}
