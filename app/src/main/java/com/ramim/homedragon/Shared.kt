package com.ramim.homedragon

import android.content.Context
import android.graphics.RectF

/** Icon positions found by the accessibility service, shared with the overlay (same process). */
object IconRegistry {
    @Volatile var icons: List<RectF> = emptyList()      // screen pixels
    @Volatile var onHome: Boolean = true                // launcher is the foreground app
    @Volatile var serviceActive: Boolean = false        // accessibility service connected
    @Volatile var launcherPkg: String? = null
    var listener: (() -> Unit)? = null                  // always called on the main thread
}

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("dragon", Context.MODE_PRIVATE)

    fun scalePct(c: Context) = sp(c).getInt("scale", 100)
    fun cols(c: Context) = sp(c).getInt("cols", 4)
    fun rows(c: Context) = sp(c).getInt("rows", 6)
    fun enabled(c: Context) = sp(c).getBoolean("enabled", false)

    fun setScalePct(c: Context, v: Int) = sp(c).edit().putInt("scale", v).apply()
    fun setCols(c: Context, v: Int) = sp(c).edit().putInt("cols", v).apply()
    fun setRows(c: Context, v: Int) = sp(c).edit().putInt("rows", v).apply()
    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("enabled", v).apply()
}
