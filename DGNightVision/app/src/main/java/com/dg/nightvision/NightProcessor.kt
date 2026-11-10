package com.dg.nightvision

import java.util.Arrays
import java.util.Random
import kotlin.math.pow

/** Kameranın parlaklık (Y) kanalını gece görüşü / termal efekt görüntüsüne çevirir. */
class NightProcessor {
    enum class Mode { NIGHT, THERMAL, RAW }

    @Volatile var mode = Mode.NIGHT
    @Volatile var gain = 1.0f

    private var w = 0
    private var h = 0
    private var acc = FloatArray(0)
    private var out = IntArray(0)
    private var vignette = FloatArray(0)
    private val hist = IntArray(256)
    private val lut = IntArray(256)
    private val noise = FloatArray(4096).also {
        val r = Random()
        for (i in it.indices) it[i] = (r.nextGaussian() * 6.0).toFloat()
    }
    private var noiseOffset = 0
    private val inferno = buildInferno()

    fun process(y: ByteArray, width: Int, height: Int): IntArray {
        val n = width * height
        if (width != w || height != h) {
            w = width; h = height
            acc = FloatArray(n) { (y[it].toInt() and 0xFF).toFloat() }
            out = IntArray(n)
            vignette = makeVignette(width, height)
        }

        val m = mode
        if (m == Mode.RAW) {
            for (i in 0 until n) {
                val v = y[i].toInt() and 0xFF
                out[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
            return out
        }

        // 1) Kareleri ortala: karanlıktaki karıncalanmayı azaltır
        Arrays.fill(hist, 0)
        for (i in 0 until n) {
            val v = (y[i].toInt() and 0xFF).toFloat()
            val a = acc[i] * 0.6f + v * 0.4f
            acc[i] = a
            hist[a.toInt().coerceIn(0, 255)]++
        }

        // 2) Otomatik seviye: en karanlık %1 siyah, en parlak %0.5 beyaz olsun
        var lo = 0; var c = 0
        val loT = n / 100
        while (lo < 254 && c + hist[lo] < loT) { c += hist[lo]; lo++ }
        var hi = 255; c = 0
        val hiT = n / 200
        while (hi > lo + 1 && c + hist[hi] < hiT) { c += hist[hi]; hi-- }
        val range = (hi - lo).coerceAtLeast(8).toFloat()

        // 3) Gerilme + gamma + kullanıcı parlaklığı -> tablo
        val g0 = gain
        for (v in 0..255) {
            val t = ((v - lo) / range).coerceIn(0f, 1f)
            val o = t.toDouble().pow(1.0 / 1.8).toFloat() * g0 * 255f
            lut[v] = o.coerceIn(0f, 255f).toInt()
        }

        if (m == Mode.THERMAL) {
            for (i in 0 until n) out[i] = inferno[lut[acc[i].toInt().coerceIn(0, 255)]]
            return out
        }

        // 4) Gece görüşü: yeşil ton + kenar karartma + tane
        noiseOffset = (noiseOffset + 1237) and 4095
        for (i in 0 until n) {
            var g = lut[acc[i].toInt().coerceIn(0, 255)] * vignette[i] +
                noise[(i * 7 + noiseOffset) and 4095]
            if (g < 0f) g = 0f else if (g > 255f) g = 255f
            val gi = g.toInt()
            val r = (gi * 0.4f).toInt()
            val b = (gi * 0.25f).toInt()
            out[i] = (0xFF shl 24) or (r shl 16) or (gi shl 8) or b
        }
        return out
    }

    private fun makeVignette(w: Int, h: Int): FloatArray {
        val v = FloatArray(w * h)
        val cx = w / 2f; val cy = h / 2f
        for (yy in 0 until h) for (xx in 0 until w) {
            val dx = (xx - cx) / cx
            val dy = (yy - cy) / cy
            v[yy * w + xx] = (1f - 0.35f * (dx * dx + dy * dy)).coerceIn(0.3f, 1f)
        }
        return v
    }

    private fun buildInferno(): IntArray {
        val a = arrayOf(
            intArrayOf(0, 0, 4), intArrayOf(40, 11, 84), intArrayOf(101, 21, 110),
            intArrayOf(159, 42, 99), intArrayOf(212, 72, 66), intArrayOf(245, 125, 21),
            intArrayOf(250, 193, 39), intArrayOf(252, 255, 164)
        )
        val p = IntArray(256)
        val seg = 255f / (a.size - 1)
        for (i in 0..255) {
            val k = (i / seg).toInt().coerceAtMost(a.size - 2)
            val t = (i - k * seg) / seg
            val r = (a[k][0] + (a[k + 1][0] - a[k][0]) * t).toInt()
            val g = (a[k][1] + (a[k + 1][1] - a[k][1]) * t).toInt()
            val b = (a[k][2] + (a[k + 1][2] - a[k][2]) * t).toInt()
            p[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return p
    }
}
