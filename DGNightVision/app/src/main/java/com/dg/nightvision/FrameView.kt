package com.dg.nightvision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.view.View
import kotlin.math.min

/** İşlenmiş kareyi doğru yönde ve ekrana sığdırarak çizer. */
class FrameView(ctx: Context) : View(ctx) {
    @Volatile var rotationDeg = 0
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
        val rot = rotationDeg
        val bw = if (rot % 180 == 0) b.width else b.height
        val bh = if (rot % 180 == 0) b.height else b.width
        val s = min(width.toFloat() / bw, height.toFloat() / bh)
        m.reset()
        m.postTranslate(-b.width / 2f, -b.height / 2f)
        m.postRotate(rot.toFloat())
        m.postScale(s, s)
        m.postTranslate(width / 2f, height / 2f)
        synchronized(b) { c.drawBitmap(b, m, paint) }
    }
}
