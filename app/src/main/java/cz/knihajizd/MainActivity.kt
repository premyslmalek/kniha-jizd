package cz.knihajizd

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.FileOutputStream
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
    /** Vozidlo, pro které se zobrazují přehledy (0 = všechna), a vozidlo předvolené pro ručně spuštěnou jízdu. */
    private var viewVehicle = 0L
    private var manualVehicle = 0L
    /** Jízdy vybrané dlouhým podržením ke sloučení. */
    private val selected = LinkedHashSet<Long>()
    private var listScroll: ScrollView? = null
    private var restoreY = 0
    private var dashYearly = false
    private var dashYear = Calendar.getInstance().get(Calendar.YEAR)

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
        if (db.vehicle(prefs.defaultVehicle) == null) prefs.defaultVehicle = db.vehicles().firstOrNull()?.id ?: 1L
        viewVehicle = prefs.defaultVehicle
        manualVehicle = prefs.defaultVehicle
        db.vehicleFilter = viewVehicle
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
        if (screen == "list" && selected.isNotEmpty()) {
            selected.clear(); render()
        } else if (screen == "list") {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        } else goBack()
    }

    private fun goBack() {
        if (screen == "detail") saveNote()
        if (screen == "settings") saveSettings()
        // rozpracovaná neuložená účtenka: její fotka se neponechává
        if (screen == "fuelEdit" && fuelDraft.id == 0L) deletePhoto(fuelDraft.photo)
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
            "tripNew" -> tripNewScreen()
            "dash" -> dashScreen()
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

    // ---------- vozidla ----------

    private fun vehLabel(id: Long): String =
        if (id == 0L) "Všechna vozidla" else db.vehicle(id)?.label ?: "Neznámé vozidlo"

    private fun vehShort(id: Long): String = db.vehicle(id)?.short ?: ""

    private fun curOdo(): Odo? = db.vehicle(viewVehicle)?.let { Odo(db, it) }

    private fun setViewVehicle(id: Long) {
        viewVehicle = id
        db.vehicleFilter = id
    }

    /** Výběr vozidla ze seznamu; s volbou "Všechna vozidla", pokud to dává smysl. */
    private fun vehicleDialog(includeAll: Boolean, onPick: (Long) -> Unit) {
        val list = db.vehicles()
        val ids = (if (includeAll) listOf(0L) else emptyList()) + list.map { it.id }
        val names = ids.map { id ->
            vehLabel(id) + (if (id != 0L && id == prefs.defaultVehicle) "  (výchozí)" else "")
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Vozidlo")
            .setItems(names) { _, which -> onPick(ids[which]) }
            .show()
    }

    /** Tlačítko s názvem vozidla, které otevře výběr. */
    private fun vehicleButton(prefix: String, id: Long, dark: Boolean, includeAll: Boolean, onPick: (Long) -> Unit): TextView {
        val b = tv(prefix + vehLabel(id) + "  ▾", 14f, if (dark) Color.WHITE else INK, true)
        b.gravity = Gravity.CENTER
        b.background = bg(if (dark) INK2 else Color.WHITE, 12, if (dark) MUTED else LINE)
        b.setPadding(dp(12), dp(12), dp(12), dp(12))
        b.isClickable = true
        b.setOnClickListener { vehicleDialog(includeAll, onPick) }
        return b
    }

    /** Založení nebo úprava vozidla. */
    private fun vehicleEditDialog(v: Vehicle?) {
        val name = field(v?.name ?: "", "název, např. Škoda Octavia", null)
        val plate = field(v?.plate ?: "", "SPZ, např. 5M2 4871", null)
        val odo = field(if (v != null && v.odoStart > 0) v.odoStart.toString() else "", "počáteční stav tachometru v km", "0123456789")
        val wrap = vbox()
        wrap.setPadding(dp(20), dp(8), dp(20), 0)
        wrap.addView(name)
        wrap.addView(plate, lp(MATCH, WRAP, 0f, 8))
        wrap.addView(odo, lp(MATCH, WRAP, 0f, 8))
        val home = field(v?.home ?: "", "výchozí místo – kde vůz garážuje", null)
        wrap.addView(home, lp(MATCH, WRAP, 0f, 8))
        wrap.addView(tv("Počáteční stav je stav tachometru před první jízdou tohoto vozu zaznamenanou v aplikaci.", 12f, MUTED), lp(MATCH, WRAP, 0f, 6))
        val b = AlertDialog.Builder(this)
            .setTitle(if (v == null) "Nové vozidlo" else "Úprava vozidla")
            .setView(wrap)
            .setPositiveButton("Uložit") { _, _ ->
                val n = name.text.toString().trim()
                if (n.isEmpty()) toast("Vozidlo nebylo uloženo – zadejte název.")
                else {
                    db.vehicleSave(
                        Vehicle(v?.id ?: 0L, n, plate.text.toString().trim().uppercase(), odo.text.toString().trim().toIntOrNull() ?: 0, home.text.toString().trim())
                    )
                    saveSettings(); render()
                }
            }
            .setNegativeButton("Zrušit", null)
        if (v != null) b.setNeutralButton("Smazat") { _, _ ->
            when {
                db.vehicles().size <= 1 -> toast("Poslední vozidlo smazat nelze.")
                v.id == prefs.defaultVehicle -> toast("Výchozí vozidlo smazat nelze. Nejdřív nastavte jako výchozí jiné.")
                db.vehicleUsed(v.id) -> toast("Vozidlo má zapsané jízdy nebo účtenky, proto ho smazat nelze.")
                else -> {
                    db.vehicleDelete(v.id)
                    if (viewVehicle == v.id) setViewVehicle(prefs.defaultVehicle)
                    if (manualVehicle == v.id) manualVehicle = prefs.defaultVehicle
                    saveSettings(); render()
                }
            }
        }
        b.show()
    }

    /** Stav tachometru: [na začátku měsíce, na konci měsíce, aktuální stav vozu]. */
    private fun odometer(year: Int, month0: Int): LongArray? {
        val o = curOdo() ?: return null
        val a = o.state(db.monthStart(year, month0)) ?: return null
        val b = o.state(monthEnd(year, month0)) ?: return null
        val c = o.state(Long.MAX_VALUE) ?: return null
        return longArrayOf(Math.round(a), Math.round(b), Math.round(c))
    }

    private fun signed(v: Long) = (if (v >= 0) "+" else "−") + km0(Math.abs(v))

    /** Zadání skutečného stavu tachometru pro zobrazený měsíc; rozdíl proti záznamům se dorovná. */
    private fun readingDialog(year: Int, month0: Int) {
        if (viewVehicle == 0L) { toast("Nejdřív vyberte jedno vozidlo."); return }
        val vid = viewVehicle
        val now = System.currentTimeMillis()
        val to = monthEnd(year, month0)
        if (db.monthStart(year, month0) > now) { toast("Tento měsíc ještě nezačal."); return }
        val key = ym(year, month0)
        val existing = curOdo()?.reading(key)
        val e = numberField(existing?.km ?: 0)
        if (existing == null) e.setText("")
        e.hint = "stav tachometru v km"
        val wrap = FrameLayout(this)
        wrap.setPadding(dp(20), dp(8), dp(20), 0)
        wrap.addView(e)
        val b = AlertDialog.Builder(this)
            .setTitle("Skutečný stav tachometru – " + vehShort(vid) + ", " + MONTHS[month0] + " " + year)
            .setMessage(
                if (to <= now) "Zadejte stav tachometru na konci měsíce."
                else "Zadejte aktuální stav tachometru. Další jízdy v tomto měsíci se k němu přičtou."
            )
            .setView(wrap)
            .setPositiveButton("Uložit") { _, _ ->
                val km = e.text.toString().trim().toIntOrNull()
                if (km != null && km > 0) { db.setReading(vid, key, km, minOf(now, to)); render() }
                else toast("Stav nebyl uložen – zadejte číslo.")
            }
            .setNegativeButton("Zrušit", null)
        if (existing != null) b.setNeutralButton("Smazat") { _, _ -> db.deleteReading(vid, key); render() }
        b.show()
    }

    private fun listScreen(): View {
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH)
        val trips = db.month(year, month)
        selected.retainAll(trips.map { it.id }.toSet())
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
        top.addView(
            vehicleButton("", viewVehicle, true, true) { setViewVehicle(it); render() },
            lp(MATCH, WRAP, 0f, 8)
        )
        page.addView(top)
        val km = trips.sumOf { it.km }
        val kmS = trips.filter { it.status == "S" }.sumOf { it.km }
        val stats = tv("Jízd: ${trips.size}  ·  Celkem ${f1(km)} km  ·  Služebně ${f1(kmS)} km", 14f, ON_DARK)
        stats.gravity = Gravity.CENTER
        head.addView(stats, lp(MATCH, WRAP, 0f, 4))
        val odo = odometer(year, month)
        val odoText = if (viewVehicle == 0L) "Tachometr se zobrazuje po výběru jednoho vozidla."
        else if (odo == null) "Počáteční stav tachometru zadáte u vozidla v nastavení."
        else "Tachometr v měsíci: ${km0(odo[0])} → ${km0(odo[1])} km\nAktuální stav vozu: ${km0(odo[2])} km"
        val odoView = tv(odoText, 14f, if (odo == null) ON_DARK else Color.WHITE, odo != null)
        odoView.gravity = Gravity.CENTER
        head.addView(odoView, lp(MATCH, WRAP, 0f, 8))
        val odoCalc = curOdo()
        val reading = odoCalc?.reading(ym(year, month))
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
        if (viewVehicle != 0L) head.addView(rb, lp(MATCH, WRAP, 0f, 10))
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
            if (db.vehicle(manualVehicle) == null) manualVehicle = prefs.defaultVehicle
            if (active) ctl.addView(tv("Vůz: " + vehLabel(manualVehicle), 14f, MUTED), lp(MATCH, WRAP, 0f, 10))
            else ctl.addView(
                vehicleButton("Vůz: ", manualVehicle, false, false) { manualVehicle = it; render() },
                lp(MATCH, WRAP, 0f, 10)
            )
            ctl.addView(
                btn(if (active) "Ukončit jízdu" else "Zahájit jízdu", if (active) RED else GREEN, Color.WHITE) {
                    if (TrackingService.tripActive) TrackingService.send(this, TrackingService.ACTION_MANUAL_STOP)
                    else TrackingService.send(this, TrackingService.ACTION_MANUAL_START, manualStatus, manualVehicle)
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
        val addLp = lp(MATCH, WRAP, 0f, 10)
        addLp.leftMargin = dp(16); addLp.rightMargin = dp(16)
        content.addView(btn("+ Přidat jízdu ručně", Color.WHITE, INK, LINE) { screen = "tripNew"; render() }, addLp)

        val list = vbox()
        list.setPadding(dp(16), dp(4), dp(16), dp(16))
        // jízdy a tankování v jednom sledu podle data, nejnovější nahoře
        val fuels = db.fuelMonth(year, month)
        val items = (trips.map { Pair<Long, Any>(it.startTs, it) } + fuels.map { Pair<Long, Any>(it.ts, it) })
            .sortedByDescending { it.first }
        if (items.isEmpty()) {
            val e = tv("V tomto měsíci zatím není žádná jízda ani tankování.", 15f, MUTED)
            e.gravity = Gravity.CENTER
            list.addView(e, lp(MATCH, WRAP, 0f, 40))
        }
        var lastDay = ""
        for ((ts, item) in items) {
            val day = dayFmt.format(Date(ts)).replaceFirstChar { it.uppercase() }
            if (day != lastDay) {
                list.addView(tv(day, 14f, INK, true), lp(MATCH, WRAP, 0f, 14))
                lastDay = day
            }
            list.addView(if (item is Trip) tripCard(item) else fuelCard(item as Fuel), lp(MATCH, WRAP, 0f, 8))
        }
        val scroll = ScrollView(this)
        content.addView(list)
        scroll.addView(content)
        listScroll = scroll
        if (restoreY > 0) {
            val y = restoreY
            restoreY = 0
            scroll.post { scroll.scrollTo(0, y) }
        }
        page.addView(scroll, lp(MATCH, 0, 1f))

        val bottom = hbox()
        bottom.setBackgroundColor(Color.WHITE)
        bottom.setPadding(dp(16), dp(10), dp(16), dp(10))
        bottom.setPadding(dp(12), dp(10), dp(12), dp(10))
        val navBtn = { label: String, fill: Int, fg: Int, stroke: Int?, go: () -> Unit ->
            val b = btn(label, fill, fg, stroke, go)
            b.textSize = 13f
            b.setPadding(dp(2), dp(14), dp(2), dp(14))
            b
        }
        bottom.addView(navBtn("Grafy", Color.WHITE, INK, LINE) { screen = "dash"; render() }, lp(0, WRAP, 1f))
        bottom.addView(navBtn("Účtenky", Color.WHITE, INK, LINE) { screen = "fuel"; render() }, lp(0, WRAP, 1f, 0, 6))
        bottom.addView(navBtn("Export", BLUE, Color.WHITE, null) { exportDialog() }, lp(0, WRAP, 1f, 0, 6))
        bottom.addView(navBtn("Nastavení", Color.WHITE, INK, LINE) { screen = "settings"; render() }, lp(0, WRAP, 1.3f, 0, 6))
        if (selected.isNotEmpty()) {
            // režim výběru: místo spodní nabídky akce nad vybranými jízdami
            bottom.removeAllViews()
            bottom.orientation = LinearLayout.VERTICAL
            val selKm = trips.filter { selected.contains(it.id) }.sumOf { it.km }
            val info = tv("Vybráno jízd: " + selected.size + "  ·  celkem " + f1(selKm) + " km", 15f, INK, true)
            info.gravity = Gravity.CENTER
            bottom.addView(info)
            val acts = hbox()
            val small = { b: TextView -> b.textSize = 13f; b.setPadding(dp(2), dp(14), dp(2), dp(14)); b }
            acts.addView(small(btn("Zrušit výběr", Color.WHITE, INK, LINE) { selected.clear(); render() }), lp(0, WRAP, 1f))
            acts.addView(small(btn("Smazat", Color.WHITE, RED, RED) { deleteSelectedDialog() }), lp(0, WRAP, 0.8f, 0, 6))
            val can = selected.size == 2
            acts.addView(
                small(btn("Sloučit jízdy", if (can) BLUE else GREY_BG, if (can) Color.WHITE else MUTED) {
                    if (!can) toast("Sloučit lze právě dvě jízdy.") else mergeDialog()
                }), lp(0, WRAP, 1.1f, 0, 6)
            )
            bottom.addView(acts, lp(MATCH, WRAP, 0f, 8))
        }
        page.addView(bottom)
        return page
    }

    /** Hromadné smazání vybraných jízd po potvrzení. */
    private fun deleteSelectedDialog() {
        val trips = selected.mapNotNull { db.get(it) }
        if (trips.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Smazat vybrané jízdy?")
            .setMessage(
                "Smaže se jízd: " + trips.size + ", celkem " + f1(trips.sumOf { it.km }) + " km.\n\n" +
                    "Jízdy zmizí z přehledu, součtů, tachometru i exportu. Akci nelze vrátit."
            )
            .setPositiveButton("Smazat") { _, _ ->
                db.deleteTrips(selected.toList())
                selected.clear()
                render()
            }
            .setNegativeButton("Ponechat", null)
            .show()
    }

    // ---------- adresář cílů ----------

    /** Výběr firmy z adresáře s hledáním podle názvu, ulice nebo města; seznam jde posouvat přes všechny položky. */
    private fun contactDialog(onPick: (Contact) -> Unit) {
        val all = db.contacts()
        if (all.isEmpty()) { toast("Adresář cílů je prázdný. Nahrajete ho v nastavení ze souboru CSV."); return }
        val search = field("", "hledat firmu nebo město", null)
        var hits = all
        val km = { v: Double -> if (v > 0.0) f1(v) + " km" else "neuvedeno" }
        // ListView vykresluje jen viditelné řádky, takže zvládne i dlouhý adresář
        val adapter = object : android.widget.BaseAdapter() {
            override fun getCount() = hits.size
            override fun getItem(position: Int): Any = hits[position]
            override fun getItemId(position: Int) = hits[position].id
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val c = hits[position]
                val row = vbox()
                // vpravo místo pro široký posuvník
                row.setPadding(0, dp(10), dp(44), dp(10))
                row.addView(tv(c.name, 15f, INK, true))
                if (c.city.isNotBlank()) row.addView(tv(c.city, 13f, MUTED))
                // dvojnásobek km po dálnici, a když chybí, km mimo dálnice
                val oneWay = if (c.kmHighway > 0.0) c.kmHighway else c.kmOther
                row.addView(tv("KM tam a zpět: " + km(oneWay * 2.0), 13f, INK))
                return row
            }
        }
        val list = android.widget.ListView(this)
        list.adapter = adapter
        list.setFastScrollStyle(R.style.WideFastScroll)
        list.isFastScrollEnabled = true
        list.isFastScrollAlwaysVisible = true
        list.isVerticalScrollBarEnabled = false
        val count = tv("Firem: " + all.size, 12f, MUTED)
        val wrap = vbox()
        wrap.setPadding(dp(20), dp(8), dp(20), 0)
        wrap.addView(search)
        wrap.addView(count, lp(MATCH, WRAP, 0f, 6))
        wrap.addView(list, lp(MATCH, dp(420), 0f, 4))
        val dialog = AlertDialog.Builder(this).setTitle("Vybrat cíl z adresáře").setView(wrap)
            .setNegativeButton("Zrušit", null).create()
        list.setOnItemClickListener { _, _, position, _ -> dialog.dismiss(); onPick(hits[position]) }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val needle = (s?.toString() ?: "").trim().lowercase()
                hits = all.filter { needle.isEmpty() || (it.name + " " + it.street + " " + it.city).lowercase().contains(needle) }
                count.text = if (needle.isEmpty()) "Firem: " + all.size else "Nalezeno: " + hits.size + " z " + all.size
                adapter.notifyDataSetChanged()
            }
        })
        dialog.show()
    }

    private fun pickContactsCsv() {
        val i = Intent(Intent.ACTION_GET_CONTENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        try {
            startActivityForResult(i, 10)
        } catch (e: Exception) {
            toast("V telefonu chybí aplikace pro výběr souborů.")
        }
    }

    private fun importContacts(uri: Uri) {
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            val list = if (bytes == null) emptyList() else ContactsCsv.parse(ContactsCsv.decode(bytes))
            if (list.isEmpty()) { toast("V souboru se nepodařilo najít žádnou firmu. Očekávám sloupce Název firmy, Ulice, Město, PSČ."); return }
            val go = { db.contactsReplace(list); toast("Do adresáře nahráno firem: " + list.size); render() }
            val old = db.contacts().size
            if (old == 0) go()
            else AlertDialog.Builder(this)
                .setTitle("Nahradit adresář?")
                .setMessage("Stávajících " + old + " firem se nahradí " + list.size + " firmami ze souboru.")
                .setPositiveButton("Nahradit") { _, _ -> go() }
                .setNegativeButton("Zrušit", null)
                .show()
        } catch (e: Exception) {
            toast("Soubor se nepodařilo načíst.")
        }
    }

    /** Potvrzení a provedení sloučení vybraných jízd. */
    private fun mergeDialog() {
        val problem = db.mergeProblem(selected.toList())
        if (problem != null) { toast(problem); return }
        val trips = selected.mapNotNull { db.get(it) }.sortedBy { it.startTs }
        val first = trips.first()
        val last = trips.last()
        val msg = first.startPlace + " → " + first.endPlace + "\n" +
            dateFmt.format(Date(first.startTs)) + " " + timeFmt.format(Date(first.startTs)) + " – " + timeFmt.format(Date(last.endTs)) + "\n" +
            f1(trips.sumOf { it.km }) + " km\nPoznámka: " + ROUND_NOTE + "\n\n" +
            "Původní jízdy se nahradí jednou sloučenou. Akci nelze vrátit."
        AlertDialog.Builder(this)
            .setTitle("Sloučit dvě jízdy do jedné?")
            .setMessage(msg)
            .setPositiveButton("Sloučit") { _, _ ->
                if (!db.mergeTrips(selected.toList())) toast("Tyto dvě jízdy nejdou sloučit.")
                selected.clear()
                render()
            }
            .setNegativeButton("Zrušit", null)
            .show()
    }

    /** Tankování v přehledu jízd: zelená karta, aby se na první pohled lišila od jízdy. */
    private fun fuelCard(f: Fuel): View {
        val c = vbox()
        c.background = bg(Color.parseColor("#E3F1E8"), 16, GREEN)
        c.setPadding(dp(14), dp(12), dp(14), dp(12))
        val top = hbox()
        top.gravity = Gravity.CENTER_VERTICAL
        top.addView(tv(timeFmt.format(Date(f.ts)) + "  ·  TANKOVÁNÍ  ·  " + vehShort(f.vehicleId), 12f, GREEN, true), lp(0, WRAP, 1f))
        val amount = tv(money(f.priceVat) + " Kč", 15f, GREEN, true)
        top.addView(amount)
        c.addView(top)
        c.addView(tv(String.format(cs, "%.2f", f.liters) + " l  ·  " + money(f.priceVat) + " Kč vč. DPH", 15f, INK, true), lp(MATCH, WRAP, 0f, 6))
        c.addView(tv(f.place.ifBlank { "Čerpací stanice neuvedena" }, 13f, MUTED), lp(MATCH, WRAP, 0f, 2))
        c.isClickable = true
        c.setOnClickListener {
            if (selected.isEmpty()) { fuelDraft = f; fuelMsg = ""; screen = "fuelEdit"; render() }
        }
        return c
    }

    private fun tripCard(t: Trip): View {
        val c = card()
        val top = hbox()
        top.gravity = Gravity.CENTER_VERTICAL
        top.addView(
            tv(timeFmt.format(Date(t.startTs)) + " – " + timeFmt.format(Date(t.endTs)) + "  ·  " + vehShort(t.vehicleId), 13f, MUTED),
            lp(0, WRAP, 1f)
        )
        top.addView(chip(t.status))
        c.addView(top)
        c.addView(tv(t.startPlace, 15f, INK, true), lp(MATCH, WRAP, 0f, 8))
        c.addView(tv("→ " + t.endPlace, 15f, INK, true), lp(MATCH, WRAP, 0f, 2))
        val meta = f1(t.km) + " km  ·  Ø " + Math.round(t.avgKmh) + " km/h  ·  " + t.minutes + " min" +
            (if (t.manual) "  ·  ručně" else "")
        c.addView(tv(meta, 13f, MUTED), lp(MATCH, WRAP, 0f, 8))
        if (selected.contains(t.id)) c.background = bg(BLUE_BG, 16, BLUE)
        // výběr se přepne bez odskočení seznamu na začátek
        val toggle = {
            if (!selected.remove(t.id)) selected.add(t.id)
            restoreY = listScroll?.scrollY ?: 0
            render()
        }
        c.isClickable = true
        c.isLongClickable = true
        c.setOnClickListener {
            if (selected.isNotEmpty()) toggle()
            else { detailId = t.id; screen = "detail"; render() }
        }
        c.setOnLongClickListener { toggle(); true }
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
                .setNeutralButton("Z adresáře") { _, _ ->
                    contactDialog { c ->
                        saveNote()
                        if (start) db.setPlaces(t.id, c.place, t.endPlace) else db.setPlaces(t.id, t.startPlace, c.place)
                        render()
                    }
                }
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

        body.addView(tv("Vozidlo", 14f, INK, true), lp(MATCH, WRAP, 0f, 16))
        body.addView(
            vehicleButton("", t.vehicleId, false, false) { saveNote(); db.setTripVehicle(t.id, it); render() },
            lp(MATCH, WRAP, 0f, 8)
        )

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

        body.addView(tv("Vozidla", 14f, INK, true), lp(MATCH, WRAP, 0f, 18))
        for (v in db.vehicles()) {
            val isDef = v.id == prefs.defaultVehicle
            val c = card()
            c.addView(tv(v.label + (if (isDef) "  ·  výchozí" else ""), 16f, INK, true))
            c.addView(
                tv(
                    (if (v.odoStart > 0) "Počáteční stav tachometru: " + km0(v.odoStart.toLong()) + " km" else "Počáteční stav tachometru není zadán") +
                        "\nVýchozí místo: " + v.home.ifBlank { "není zadáno" }, 13f, MUTED
                ),
                lp(MATCH, WRAP, 0f, 2)
            )
            val acts = hbox()
            acts.addView(btn("Upravit", Color.WHITE, INK, LINE) { vehicleEditDialog(v) }, lp(0, WRAP, 1f))
            if (!isDef) acts.addView(
                btn("Nastavit výchozí", Color.WHITE, BLUE, LINE) {
                    saveSettings(); prefs.defaultVehicle = v.id; manualVehicle = v.id; render()
                }, lp(0, WRAP, 1.4f, 0, 8)
            )
            c.addView(acts, lp(MATCH, WRAP, 0f, 10))
            body.addView(c, lp(MATCH, WRAP, 0f, 8))
        }
        body.addView(btn("+ Přidat vozidlo", Color.WHITE, INK, LINE) { vehicleEditDialog(null) }, lp(MATCH, WRAP, 0f, 8))
        body.addView(
            tv("Na výchozí vozidlo se zapisují automaticky zaznamenané jízdy. U každé jízdy lze vozidlo změnit i zpětně.", 13f, MUTED),
            lp(MATCH, WRAP, 0f, 6)
        )

        body.addView(tv("Adresář cílů", 14f, INK, true), lp(MATCH, WRAP, 0f, 18))
        val book = card()
        val nContacts = db.contacts().size
        book.addView(tv(if (nContacts == 0) "Adresář je prázdný" else "Firem v adresáři: $nContacts", 15f, INK, true))
        book.addView(
            tv("Soubor CSV se sloupci Název firmy, Ulice s č.p., Město, PSČ, vzdálenost po dálnici a vzdálenost mimo dálnice. Z adresáře pak vyberete cíl při ručním zadání jízdy nebo při úpravě cíle jízdy.", 13f, MUTED),
            lp(MATCH, WRAP, 0f, 2)
        )
        book.addView(btn("Nahrát CSV s cíli", Color.WHITE, INK, LINE) { saveSettings(); pickContactsCsv() }, lp(MATCH, WRAP, 0f, 10))
        body.addView(book, lp(MATCH, WRAP, 0f, 8))

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

    // ---------- grafy ----------

    /** Přehled ujetých km a nákladů na palivo: po měsících zvoleného roku, nebo po letech. */
    private fun dashScreen(): View {
        val page = vbox()
        page.setBackgroundColor(GROUND)
        page.setPadding(dp(16), dp(16), dp(16), dp(16))
        page.addView(topBar("Grafy"))
        val body = vbox()

        val toggle = hbox()
        val tBtn = { label: String, on: Boolean, go: () -> Unit ->
            btn(label, if (on) INK else Color.WHITE, if (on) Color.WHITE else INK, if (on) INK else LINE, go)
        }
        toggle.addView(tBtn("Po měsících", !dashYearly) { dashYearly = false; render() }, lp(0, WRAP, 1f))
        toggle.addView(tBtn("Po letech", dashYearly) { dashYearly = true; render() }, lp(0, WRAP, 1f, 0, 8))
        body.addView(vehicleButton("", viewVehicle, false, true) { setViewVehicle(it); render() }, lp(MATCH, WRAP, 0f, 14))
        body.addView(toggle, lp(MATCH, WRAP, 0f, 10))

        val labels: List<String>
        val trips: List<Trip>
        val fuel: List<Fuel>
        val bucket: (Long) -> Int
        val c = Calendar.getInstance()
        if (!dashYearly) {
            val nav = hbox()
            nav.gravity = Gravity.CENTER_VERTICAL
            nav.addView(btn("‹", Color.WHITE, INK, LINE) { dashYear--; render() }, lp(dp(56), WRAP))
            val yt = tv(dashYear.toString(), 22f, INK, true)
            yt.gravity = Gravity.CENTER
            nav.addView(yt, lp(0, WRAP, 1f))
            nav.addView(btn("›", Color.WHITE, INK, LINE) { dashYear++; render() }, lp(dp(56), WRAP))
            body.addView(nav, lp(MATCH, WRAP, 0f, 10))
            val from = db.monthStart(dashYear, 0)
            val to = db.monthStart(dashYear + 1, 0)
            trips = db.tripsRange(from, to)
            fuel = db.fuelRange(from, to)
            labels = (1..12).map { it.toString() }
            bucket = { ts -> c.timeInMillis = ts; c.get(Calendar.MONTH) }
        } else {
            trips = db.tripsRange(0L, Long.MAX_VALUE)
            fuel = db.fuelRange(0L, Long.MAX_VALUE)
            val yearOf = { ts: Long -> c.timeInMillis = ts; c.get(Calendar.YEAR) }
            val nowYear = Calendar.getInstance().get(Calendar.YEAR)
            val first = (trips.map { yearOf(it.startTs) } + fuel.map { yearOf(it.ts) } + listOf(nowYear)).min()
            val start = maxOf(first, nowYear - 9)
            labels = (start..nowYear).map { it.toString() }
            bucket = { ts -> yearOf(ts) - start }
        }
        val n = labels.size
        val kmS = DoubleArray(n)
        val kmO = DoubleArray(n)
        val cost = DoubleArray(n)
        for (t in trips) {
            val i = bucket(t.startTs)
            if (i in 0 until n) { if (t.status == "S") kmS[i] += t.km else kmO[i] += t.km }
        }
        var liters = 0.0
        for (f in fuel) {
            val i = bucket(f.ts)
            if (i in 0 until n) { cost[i] += f.priceVat; liters += f.liters }
        }
        val totalS = kmS.sum()
        val totalO = kmO.sum()
        val totalKm = totalS + totalO
        val totalCost = cost.sum()

        val sum = card()
        sum.addView(tv("Ujeto celkem: " + f1(totalKm) + " km", 16f, INK, true))
        sum.addView(tv("Služebně " + f1(totalS) + " km  ·  soukromě a nezařazeno " + f1(totalO) + " km", 13f, MUTED), lp(MATCH, WRAP, 0f, 2))
        sum.addView(tv("Palivo: " + String.format(cs, "%.2f", liters) + " l  ·  " + money(totalCost) + " Kč vč. DPH", 16f, INK, true), lp(MATCH, WRAP, 0f, 10))
        if (totalKm > 0.0 && liters > 0.0) {
            sum.addView(
                tv(
                    "Orientačně " + String.format(cs, "%.1f", liters / totalKm * 100.0) + " l/100 km  ·  " +
                        String.format(cs, "%.2f", totalCost / totalKm) + " Kč/km (z natankovaného paliva a zaznamenaných km)",
                    13f, MUTED
                ), lp(MATCH, WRAP, 0f, 2)
            )
        }
        body.addView(sum, lp(MATCH, WRAP, 0f, 12))

        val orange = Color.parseColor("#D9822B")
        val kmCard = card()
        kmCard.addView(tv("Ujeté km", 15f, INK, true))
        val legend = hbox()
        legend.gravity = Gravity.CENTER_VERTICAL
        val dot = { color: Int -> val v = View(this); v.background = bg(color, 3); v }
        legend.addView(dot(BLUE), lp(dp(12), dp(12)))
        legend.addView(tv("služební", 12f, MUTED), lp(WRAP, WRAP, 0f, 0, 6))
        legend.addView(dot(orange), lp(dp(12), dp(12), 0f, 0, 14))
        legend.addView(tv("soukromé a nezařazené", 12f, MUTED), lp(WRAP, WRAP, 0f, 0, 6))
        kmCard.addView(legend, lp(MATCH, WRAP, 0f, 4))
        kmCard.addView(BarChart(this, labels, kmS, kmO, BLUE, orange), lp(MATCH, dp(190), 0f, 8))
        body.addView(kmCard, lp(MATCH, WRAP, 0f, 10))

        val costCard = card()
        costCard.addView(tv("Náklady na palivo (Kč vč. DPH)", 15f, INK, true))
        costCard.addView(BarChart(this, labels, cost, null, GREEN, GREEN), lp(MATCH, dp(190), 0f, 8))
        body.addView(costCard, lp(MATCH, WRAP, 0f, 10))

        if (totalKm == 0.0 && totalCost == 0.0) {
            body.addView(tv("V tomto období zatím nejsou žádné jízdy ani účtenky.", 14f, MUTED), lp(MATCH, WRAP, 0f, 12))
        }

        val scroll = ScrollView(this)
        scroll.addView(body)
        page.addView(scroll, lp(MATCH, 0, 1f))
        return page
    }

    // ---------- ruční zadání jízdy ----------

    /** Formulář pro dodatečné zapsání jízdy; uložená jízda se zařadí mezi ostatní podle data a času. */
    private fun tripNewScreen(): View {
        val page = vbox()
        page.setBackgroundColor(GROUND)
        page.setPadding(dp(16), dp(16), dp(16), dp(16))
        page.addView(topBar("Nová jízda"))

        val body = vbox()
        // Když je za zobrazený měsíc zadaný skutečný stav tachometru, ukáže se, kolik km v záznamech chybí.
        val missing = curOdo()?.adjustment(ym(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH)))
        if (missing != null && missing > 0) {
            val m = tv(
                "V měsíci " + MONTHS[cal.get(Calendar.MONTH)] + " chybí u vozu " + vehShort(viewVehicle) + " do skutečného stavu tachometru " +
                    km0(missing) + " km. Doplňte jízdy, které aplikace nezaznamenala.", 14f, ORANGE, true
            )
            m.background = bg(ORANGE_BG, 12)
            m.setPadding(dp(12), dp(10), dp(12), dp(10))
            body.addView(m, lp(MATCH, WRAP, 0f, 14))
        }
        // předvyplní se dnešní datum a aktuální čas
        val today = Calendar.getInstance()
        val sameMonth = today.get(Calendar.YEAR) == cal.get(Calendar.YEAR) && today.get(Calendar.MONTH) == cal.get(Calendar.MONTH)
        val date = field(dmy.format(today.time), "např. 3.10.2026", "0123456789.")
        val from = field(timeFmt.format(today.time), "např. 7:30", "0123456789:.")
        val to = field("", "např. 8:15", "0123456789:.")
        // start se předvyplní výchozím místem vozidla
        val homeOf = { id: Long -> db.vehicle(id)?.home ?: "" }
        val start = field(homeOf(if (viewVehicle != 0L) viewVehicle else prefs.defaultVehicle), "např. Sněhotice", null)
        val end = field("", "např. Prostějov, Průmyslová", null)
        val km = field("", "např. 21,4", "0123456789,.")
        val note = field("", "Např. účel cesty, zákazník", null)

        // Konec jízdy se předvyplňuje ze začátku a ujetých km při průměrné rychlosti 90 km/h.
        val fillEnd = {
            val dist = num(km)
            val parts = from.text.toString().trim().replace('.', ':').split(":")
            val h = parts.getOrNull(0)?.toIntOrNull()
            val m = parts.getOrNull(1)?.toIntOrNull()
            if (dist > 0.0 && h != null && m != null && h in 0..23 && m in 0..59) {
                val endMin = minOf(h * 60 + m + maxOf(1, Math.round(dist / 90.0 * 60.0).toInt()), 23 * 60 + 59)
                to.setText(String.format(Locale.US, "%d:%02d", endMin / 60, endMin % 60))
            }
        }
        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { fillEnd() }
        }
        km.addTextChangedListener(watcher)
        from.addTextChangedListener(watcher)

        body.addView(tv("Datum", 14f, INK, true), lp(MATCH, WRAP, 0f, 14))
        body.addView(date, lp(MATCH, WRAP, 0f, 6))
        val times = hbox()
        val c1 = vbox(); c1.addView(tv("Začátek", 14f, INK, true)); c1.addView(from, lp(MATCH, WRAP, 0f, 6))
        val c2 = vbox(); c2.addView(tv("Konec", 14f, INK, true)); c2.addView(to, lp(MATCH, WRAP, 0f, 6))
        times.addView(c1, lp(0, WRAP, 1f))
        times.addView(c2, lp(0, WRAP, 1f, 0, 8))
        body.addView(times, lp(MATCH, WRAP, 0f, 12))
        body.addView(tv("Start", 14f, INK, true), lp(MATCH, WRAP, 0f, 12))
        body.addView(start, lp(MATCH, WRAP, 0f, 6))
        body.addView(tv("Cíl", 14f, INK, true), lp(MATCH, WRAP, 0f, 12))
        body.addView(end, lp(MATCH, WRAP, 0f, 6))
        body.addView(
            btn("Vybrat cíl z adresáře", Color.WHITE, INK, LINE) { contactDialog { c ->
                end.setText(c.place)
                // km se předvyplní jako cesta tam a zpět: dvojnásobek vzdálenosti po dálnici,
                // a když ta chybí, mimo dálnice; konec jízdy se z nich dopočítá
                val oneWay = if (c.kmHighway > 0.0) c.kmHighway else c.kmOther
                if (oneWay > 0.0) {
                    km.setText(String.format(cs, "%.1f", oneWay * 2.0))
                    // poznámka se přepíše jen tehdy, když ji uživatel sám nezměnil
                    val cur = note.text.toString().trim()
                    if (cur.isEmpty() || cur.startsWith(ROUND_NOTE)) note.setText(ROUND_NOTE + " – " + c.name)
                }
            } },
            lp(MATCH, WRAP, 0f, 6)
        )
        body.addView(tv("Ujeté km", 14f, INK, true), lp(MATCH, WRAP, 0f, 12))
        body.addView(km, lp(MATCH, WRAP, 0f, 6))

        body.addView(tv("Vozidlo", 14f, INK, true), lp(MATCH, WRAP, 0f, 12))
        var vid = if (viewVehicle != 0L) viewVehicle else prefs.defaultVehicle
        val vBtn = tv(vehLabel(vid) + "  ▾", 15f, INK, true)
        vBtn.background = bg(Color.WHITE, 12, LINE)
        vBtn.setPadding(dp(12), dp(12), dp(12), dp(12))
        vBtn.isClickable = true
        // výběr bez překreslení obrazovky, aby se neztratily rozepsané údaje
        vBtn.setOnClickListener {
            vehicleDialog(false) {
                // start se přepíše jen tehdy, když ho uživatel sám nezměnil
                val cur = start.text.toString().trim()
                if (cur.isEmpty() || cur == homeOf(vid)) start.setText(homeOf(it))
                vid = it
                vBtn.text = vehLabel(it) + "  ▾"
            }
        }
        body.addView(vBtn, lp(MATCH, WRAP, 0f, 6))

        body.addView(tv("Status jízdy", 14f, INK, true), lp(MATCH, WRAP, 0f, 12))
        var status = "S"
        val bS = btn("Služební", Color.WHITE, MUTED, LINE) {}
        val bP = btn("Soukromá", Color.WHITE, MUTED, LINE) {}
        // přepnutí statusu bez překreslení obrazovky, aby se neztratily rozepsané údaje
        val paint = {
            bS.background = bg(if (status == "S") BLUE_BG else Color.WHITE, 14, if (status == "S") BLUE else LINE)
            bS.setTextColor(if (status == "S") BLUE else MUTED)
            bP.background = bg(if (status == "P") ORANGE_BG else Color.WHITE, 14, if (status == "P") ORANGE else LINE)
            bP.setTextColor(if (status == "P") ORANGE else MUTED)
        }
        bS.setOnClickListener { status = "S"; paint() }
        bP.setOnClickListener { status = "P"; paint() }
        paint()
        val row = hbox()
        row.addView(bS, lp(0, WRAP, 1f))
        row.addView(bP, lp(0, WRAP, 1f, 0, 8))
        body.addView(row, lp(MATCH, WRAP, 0f, 6))

        body.addView(tv("Poznámka", 14f, INK, true), lp(MATCH, WRAP, 0f, 12))
        body.addView(note, lp(MATCH, WRAP, 0f, 6))

        body.addView(
            btn("Uložit jízdu", GREEN, Color.WHITE) {
                val fmt = SimpleDateFormat("d.M.yyyy H:mm", cs)
                fmt.isLenient = false
                val day = date.text.toString().trim().replace(" ", "")
                val parse = { e: EditText ->
                    try { fmt.parse(day + " " + e.text.toString().trim().replace('.', ':')) } catch (ex: Exception) { null }
                }
                val a = parse(from)
                val b = parse(to)
                val dist = num(km)
                val sp = start.text.toString().trim()
                val ep = end.text.toString().trim()
                if (a == null || b == null) toast("Zadejte datum ve tvaru 3.10.2026 a časy ve tvaru 7:30.")
                else if (b.time <= a.time) toast("Konec jízdy musí být později než začátek.")
                else if (sp.isEmpty() || ep.isEmpty()) toast("Vyplňte start a cíl.")
                else if (dist <= 0.0) toast("Vyplňte ujeté kilometry.")
                else {
                    val id = db.insert(a.time, b.time, 0.0, 0.0, 0.0, 0.0, sp, ep, dist * 1000.0, status, true, vid)
                    val n = note.text.toString().trim()
                    if (n.isNotEmpty()) db.setNote(id, n)
                    // přehled se přepne na měsíc uložené jízdy
                    val c = Calendar.getInstance()
                    c.time = a
                    cal.set(Calendar.YEAR, c.get(Calendar.YEAR))
                    cal.set(Calendar.MONTH, c.get(Calendar.MONTH))
                    screen = "list"
                    render()
                }
            }, lp(MATCH, WRAP, 0f, 18)
        )
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
        body.addView(vehicleButton("", viewVehicle, false, true) { setViewVehicle(it); render() }, lp(MATCH, WRAP, 0f, 14))
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
            top.addView(tv(f.place.ifBlank { "Neuvedeno" }, 16f, INK, true), lp(0, WRAP, 1f))
            top.addView(tv(dmy.format(Date(f.ts)) + " " + timeFmt.format(Date(f.ts)), 13f, MUTED))
            c.addView(top)
            c.addView(
                tv(String.format(cs, "%.2f", f.liters) + " l  ·  " + money(f.priceVat) + " Kč vč. DPH", 15f),
                lp(MATCH, WRAP, 0f, 6)
            )
            c.addView(tv(money(f.priceNoVat) + " Kč bez DPH" + (if (f.photo.isNotEmpty()) "  ·  s fotkou" else "  ·  bez fotky") + "  ·  " + vehShort(f.vehicleId), 13f, MUTED), lp(MATCH, WRAP, 0f, 2))
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

    private fun deletePhoto(path: String) {
        if (path.isNotEmpty()) try { File(path).delete() } catch (_: Exception) {}
    }

    /** Načte fotku ve správném otočení a zmenšenou tak, aby delší strana měla nejvýš zhruba 2000 bodů. */
    private fun loadBitmap(uri: Uri): Bitmap? {
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                val src = ImageDecoder.createSource(contentResolver, uri)
                return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.setTargetSampleSize(maxOf(1, maxOf(info.size.width, info.size.height) / 2000))
                }
            }
            val opts = BitmapFactory.Options()
            opts.inSampleSize = 2
            contentResolver.openInputStream(uri).use { return BitmapFactory.decodeStream(it, null, opts) }
        } catch (e: Exception) {
            return null
        }
    }

    /** Uloží fotku účtenky do úložiště aplikace a vrátí cestu k souboru (prázdnou při neúspěchu). */
    private fun savePhoto(bmp: Bitmap): String {
        return try {
            val dir = File(filesDir, "uctenky")
            dir.mkdirs()
            val f = File(dir, "uctenka_" + System.currentTimeMillis() + ".jpg")
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            f.absolutePath
        } catch (e: Exception) {
            ""
        }
    }

    /** Uloží fotku účtenky, přečte z ní text a otevře formulář s předvyplněnými údaji ke kontrole. */
    private fun readReceipt(uri: Uri) {
        val failed = "Z fotky se nepodařilo nic přečíst. Vyplňte údaje ručně."
        val bmp = loadBitmap(uri)
        if (bmp == null) {
            openFuelForm(Fuel(0, System.currentTimeMillis(), "", 0.0, 0.0, 0.0), "Fotku se nepodařilo načíst. Vyplňte údaje ručně.")
            return
        }
        val path = savePhoto(bmp)
        val empty = Fuel(0, System.currentTimeMillis(), "", 0.0, 0.0, 0.0, path)
        toast("Čtu účtenku…")
        try {
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { res ->
                    if (res.text.isBlank()) openFuelForm(empty, failed)
                    else openFuelForm(
                        Receipts.parse(res.text).copy(photo = path),
                        "Údaje jsou přečtené z fotky a mohou být chybné. Zkontrolujte je a opravte před uložením."
                    )
                }
                .addOnFailureListener { openFuelForm(empty, failed) }
        } catch (e: Exception) {
            openFuelForm(empty, failed)
        }
    }

    /** Náhled uložené fotky účtenky; klepnutím se zobrazí celá. */
    private fun photoView(path: String): View? {
        if (path.isEmpty() || !File(path).exists()) return null
        val opts = BitmapFactory.Options()
        opts.inSampleSize = 2
        val bmp = try { BitmapFactory.decodeFile(path, opts) } catch (e: Throwable) { null } ?: return null
        val iv = ImageView(this)
        iv.setImageBitmap(bmp)
        iv.adjustViewBounds = true
        iv.maxHeight = dp(240)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        iv.contentDescription = "Fotka účtenky"
        iv.isClickable = true
        iv.setOnClickListener {
            val full = try { BitmapFactory.decodeFile(path) } catch (e: Throwable) { null } ?: bmp
            val big = ImageView(this)
            big.setImageBitmap(full)
            big.adjustViewBounds = true
            val sc = ScrollView(this)
            sc.addView(big)
            AlertDialog.Builder(this).setView(sc).setPositiveButton("Zavřít", null).show()
        }
        return iv
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
        var fVid = if (f.vehicleId != 0L) f.vehicleId else if (viewVehicle != 0L) viewVehicle else prefs.defaultVehicle
        val pv0 = photoView(f.photo)
        if (pv0 != null) {
            body.addView(pv0, lp(MATCH, WRAP, 0f, 14))
            body.addView(tv("Fotka účtenky je uložená u záznamu. Klepnutím ji zvětšíte.", 12f, MUTED), lp(MATCH, WRAP, 0f, 4))
        }
        val date = field(dmy.format(Date(if (f.ts > 0) f.ts else System.currentTimeMillis())), "např. 3.10.2026", "0123456789.")
        val time = field(timeFmt.format(Date(if (f.ts > 0) f.ts else System.currentTimeMillis())), "např. 14:35", "0123456789:.")
        val station = field(f.station, "např. MOL", null)
        val address = field(f.address, "např. Olomoucká 10, 796 01 Prostějov", null)
        val liters = field(dec(f.liters), "např. 42,15", "0123456789,.")
        val vat = field(dec(f.priceVat), "např. 1560,00", "0123456789,.")
        val noVat = field(dec(f.priceNoVat), "např. 1289,26", "0123456789,.")

        body.addView(tv("Vozidlo", 14f, INK, true), lp(MATCH, WRAP, 0f, 14))
        val fvBtn = tv(vehLabel(fVid) + "  ▾", 15f, INK, true)
        fvBtn.background = bg(Color.WHITE, 12, LINE)
        fvBtn.setPadding(dp(12), dp(12), dp(12), dp(12))
        fvBtn.isClickable = true
        fvBtn.setOnClickListener { vehicleDialog(false) { fVid = it; fvBtn.text = vehLabel(it) + "  ▾" } }
        body.addView(fvBtn, lp(MATCH, WRAP, 0f, 6))
        val when2 = hbox()
        val dc = vbox(); dc.addView(tv("Datum", 14f, INK, true)); dc.addView(date, lp(MATCH, WRAP, 0f, 6))
        val tc = vbox(); tc.addView(tv("Čas", 14f, INK, true)); tc.addView(time, lp(MATCH, WRAP, 0f, 6))
        when2.addView(dc, lp(0, WRAP, 1.4f))
        when2.addView(tc, lp(0, WRAP, 1f, 0, 8))
        body.addView(when2, lp(MATCH, WRAP, 0f, 12))
        body.addView(tv("Čerpací stanice", 14f, INK, true), lp(MATCH, WRAP, 0f, 12))
        body.addView(station, lp(MATCH, WRAP, 0f, 6))
        body.addView(tv("Adresa čerpací stanice", 14f, INK, true), lp(MATCH, WRAP, 0f, 12))
        body.addView(address, lp(MATCH, WRAP, 0f, 6))
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
                val fmt = SimpleDateFormat("d.M.yyyy H:mm", cs)
                fmt.isLenient = false
                val d = try {
                    fmt.parse(date.text.toString().trim().replace(" ", "") + " " + time.text.toString().trim().replace('.', ':'))
                } catch (e: Exception) { null }
                val l = num(liters)
                val pv = num(vat)
                if (d == null) toast("Datum zadejte ve tvaru 3.10.2026 a čas ve tvaru 14:35.")
                else if (l <= 0.0 || pv <= 0.0) toast("Vyplňte počet litrů a cenu vč. DPH.")
                else {
                    val c = Calendar.getInstance()
                    c.time = d
                    db.fuelSave(Fuel(f.id, c.timeInMillis, station.text.toString().trim(), l, pv, num(noVat), f.photo, fVid, address.text.toString().trim()))
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
                        .setPositiveButton("Vymazat") { _, _ -> db.fuelDelete(f.id); deletePhoto(f.photo); screen = "fuel"; render() }
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
        if (requestCode == 10) {
            val uri = data?.data
            if (resultCode == RESULT_OK && uri != null) importContacts(uri)
            return
        }
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
        Export.plates = db.vehicles().associate { it.id to it.short }
        val scope = if (viewVehicle == 0L) "všechna vozidla" else vehLabel(viewVehicle)
        try {
            val out = contentResolver.openOutputStream(uri)
            if (out == null) { toast("Soubor se nepodařilo uložit."); return }
            out.use {
                if (pendingExport == "csv") Export.csv(it, trips, db.fuelMonth(year, month))
                else {
                    val o = odometer(year, month)
                    val odoTxt = if (o == null) null
                    else {
                        val adj = curOdo()?.adjustment(ym(year, month))
                        "Tachometr: ${km0(o[0])} → ${km0(o[1])} km" +
                            (if (adj != null) " (dorovnání ${signed(adj)} km)" else "")
                    }
                    val fuel = db.fuelMonth(year, month)
                    val extra = listOfNotNull(odoTxt, fuelSummary(fuel) + "  ·  " + money(fuel.sumOf { f -> f.priceNoVat }) + " Kč bez DPH")
                    Export.pdf(it, "Kniha jízd – " + MONTHS[month] + " " + year + " – " + scope, trips, fuel, extra)
                }
            }
            toast("Soubor je uložen.")
        } catch (e: Exception) {
            toast("Soubor se nepodařilo uložit.")
        }
    }
}
