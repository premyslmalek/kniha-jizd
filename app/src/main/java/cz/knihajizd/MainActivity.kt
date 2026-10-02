package cz.knihajizd

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
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
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
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

    private var statusView: TextView? = null
    private var noteEdit: EditText? = null
    private var thrEdit: EditText? = null
    private var minEdit: EditText? = null
    private var stopEdit: EditText? = null

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
        cal.set(Calendar.DAY_OF_MONTH, 1)
        root = FrameLayout(this)
        setContentView(root)
        render()
        if (!hasLoc()) askPerms() else if (prefs.mode == "auto") TrackingService.send(this, TrackingService.ACTION_AUTO)
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
        screen = "list"
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
        statusView = null; noteEdit = null; thrEdit = null; minEdit = null; stopEdit = null
        root.removeAllViews()
        val v = when (screen) {
            "detail" -> detailScreen()
            "settings" -> settingsScreen()
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

    private fun listScreen(): View {
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH)
        val trips = db.month(year, month)
        val page = vbox()
        page.setBackgroundColor(GROUND)

        val head = vbox()
        head.setBackgroundColor(INK)
        head.setPadding(dp(16), dp(20), dp(16), dp(16))
        val nav = hbox()
        nav.gravity = Gravity.CENTER_VERTICAL
        nav.addView(btn("‹", INK2, Color.WHITE) { cal.add(Calendar.MONTH, -1); render() }, lp(dp(56), WRAP))
        val title = tv(MONTHS[month] + " " + year, 24f, Color.WHITE, true)
        title.gravity = Gravity.CENTER
        nav.addView(title, lp(0, WRAP, 1f))
        nav.addView(btn("›", INK2, Color.WHITE) { cal.add(Calendar.MONTH, 1); render() }, lp(dp(56), WRAP))
        head.addView(nav)
        val km = trips.sumOf { it.km }
        val kmS = trips.filter { it.status == "S" }.sumOf { it.km }
        val stats = tv("Jízd: ${trips.size}  ·  Celkem ${f1(km)} km  ·  Služebně ${f1(kmS)} km", 14f, ON_DARK)
        stats.gravity = Gravity.CENTER
        head.addView(stats, lp(MATCH, WRAP, 0f, 12))
        page.addView(head)

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
        page.addView(ctl, ctlLp)

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
        scroll.addView(list)
        page.addView(scroll, lp(MATCH, 0, 1f))

        val bottom = hbox()
        bottom.setBackgroundColor(Color.WHITE)
        bottom.setPadding(dp(16), dp(10), dp(16), dp(10))
        bottom.addView(btn("Export měsíce", BLUE, Color.WHITE) { exportDialog() }, lp(0, WRAP, 1f))
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
        places.addView(tv(t.startPlace, 16f, INK, true))
        places.addView(gpsLine(t.sLat, t.sLon, "Start"))
        places.addView(tv("Cíl · " + timeFmt.format(Date(t.endTs)), 12f, MUTED), lp(MATCH, WRAP, 0f, 12))
        places.addView(tv(t.endPlace, 16f, INK, true))
        places.addView(gpsLine(t.eLat, t.eLon, "Cíl"))
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
                else Export.pdf(it, "Kniha jízd – " + MONTHS[month] + " " + year, trips)
            }
            toast("Soubor je uložen.")
        } catch (e: Exception) {
            toast("Soubor se nepodařilo uložit.")
        }
    }
}
