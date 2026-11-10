package com.dg.nightvision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.view.View
import kotlin.math.min

/** İşlenmiş kareyi çizer. VR modunda görüntüyü iki göz için yan yana çizer. */
class FrameView(ctx: Context) : View(ctx) {
    @Volatile var rotationDeg = 0
    @Volatile var vrMode = false
    @Volatile private var bmp: Bitmap? = null
    private val m = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun ensureBitmap(w: Int, h: Int): Bitmap {
        val b = bmp
        if (b != null && b.width == w && b.height == h) return b
        val nb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp = nb
        return nb
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.BLACK)
        val b = bmp ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (!vrMode) {
            drawInto(c, b, 0f, 0f, w, h)
        } else {
            // Sol göz ve sağ göz: aynı görüntü, ekranın iki yarısında
            val half = w / 2f
            drawInto(c, b, 0f, 0f, half, h)
            drawInto(c, b, half, 0f, half, h)
        }
    }

    private fun drawInto(c: Canvas, b: Bitmap, x: Float, y: Float, w: Float, h: Float) {
        val rot = rotationDeg
        val bw = if (rot % 180 == 0) b.width else b.height
        val bh = if (rot % 180 == 0) b.height else b.width
        val s = min(w / bw, h / bh)
        m.reset()
        m.postTranslate(-b.width / 2f, -b.height / 2f)
        m.postRotate(rot.toFloat())
        m.postScale(s, s)
        m.postTranslate(x + w / 2f, y + h / 2f)
        c.save()
        c.clipRect(x, y, x + w, y + h)
        synchronized(b) { c.drawBitmap(b, m, paint) }
        c.restore()
    }
}
