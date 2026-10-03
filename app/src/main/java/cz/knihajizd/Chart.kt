package cz.knihajizd

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import java.util.Locale

/** Jednoduchý sloupcový graf; volitelně skládaný ze dvou řad (a dole, b nad ní). */
class BarChart(
    ctx: Context,
    private val labels: List<String>,
    private val a: DoubleArray,
    private val b: DoubleArray?,
    private val colorA: Int,
    private val colorB: Int
) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG)
    private val axis = Paint()

    init {
        txt.textAlign = Paint.Align.CENTER
        txt.textSize = 10f * d
        txt.color = 0xFF4A524D.toInt()
        axis.color = 0xFFC9CFC8.toInt()
        axis.strokeWidth = d
    }

    private fun short(v: Double): String =
        if (v >= 10000.0) String.format(Locale("cs", "CZ"), "%.1fk", v / 1000.0) else Math.round(v).toString()

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val n = labels.size
        if (n == 0) return
        val top = 18f * d
        val bottom = height - 18f * d
        var max = 0.0
        for (i in 0 until n) max = maxOf(max, a[i] + (b?.get(i) ?: 0.0))
        val slot = width.toFloat() / n
        val bw = minOf(slot * 0.62f, 48f * d)
        c.drawLine(0f, bottom, width.toFloat(), bottom, axis)
        for (i in 0 until n) {
            val cx = slot * i + slot / 2f
            c.drawText(labels[i], cx, height - 4f * d, txt)
            if (max <= 0.0) continue
            val vb = b?.get(i) ?: 0.0
            val ha = (a[i] / max * (bottom - top)).toFloat()
            val hb = (vb / max * (bottom - top)).toFloat()
            if (ha > 0f) { bar.color = colorA; c.drawRect(cx - bw / 2f, bottom - ha, cx + bw / 2f, bottom, bar) }
            if (hb > 0f) { bar.color = colorB; c.drawRect(cx - bw / 2f, bottom - ha - hb, cx + bw / 2f, bottom - ha, bar) }
            if (a[i] + vb > 0.0) c.drawText(short(a[i] + vb), cx, bottom - ha - hb - 3f * d, txt)
        }
    }
}
