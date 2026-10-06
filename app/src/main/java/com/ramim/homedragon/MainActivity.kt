package com.ramim.homedragon

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/** One plain setup screen: three permissions, a start/stop button and three size sliders. */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var toggle: Button

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setPadding(0, dp(14), 0, dp(2))
    }

    private fun slider(max: Int, start: Int, onChange: (Int) -> Unit) = SeekBar(this).apply {
        this.max = max
        progress = start
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) onChange(p) }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }
        col.addView(TextView(this).apply { text = "Home Dragon"; textSize = 26f; gravity = Gravity.START })
        status = TextView(this).apply { textSize = 14f; setPadding(0, dp(10), 0, dp(10)) }
        col.addView(status)

        col.addView(button("1. Allow drawing over other apps") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        col.addView(button("2. Turn on the icon finder") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Open Home Dragon icon finder and switch it on.", Toast.LENGTH_LONG).show()
        })
        col.addView(button("3. Allow background running") { openBackgroundSettings() })
        toggle = button("Start dragon") { toggleService() }
        col.addView(toggle)

        col.addView(label("Dragon size"))
        col.addView(slider(100, Prefs.scalePct(this) - 50) {
            Prefs.setScalePct(this, it + 50)
            DragonService.instance?.view?.reloadPrefs()
        })
        col.addView(label("Fallback grid columns (used only if the icon finder is off)"))
        col.addView(slider(3, Prefs.cols(this) - 3) {
            Prefs.setCols(this, it + 3)
            DragonService.instance?.view?.reloadPrefs()
        })
        col.addView(label("Fallback grid rows"))
        col.addView(slider(4, Prefs.rows(this) - 4) {
            Prefs.setRows(this, it + 4)
            DragonService.instance?.view?.reloadPrefs()
        })

        setContentView(ScrollView(this).apply { addView(col) })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun a11yEnabled(): Boolean {
        val me = ComponentName(this, IconFinderService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return enabled.split(':').any { it.equals(me, ignoreCase = true) }
    }

    private fun refresh() {
        val overlay = Settings.canDrawOverlays(this)
        val a11y = a11yEnabled()
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val battery = pm.isIgnoringBatteryOptimizations(packageName)
        val running = DragonService.instance != null
        status.text = buildString {
            append(if (overlay) "Overlay permission: on\n" else "Overlay permission: OFF\n")
            append(if (a11y) "Icon finder: on\n" else "Icon finder: off (grid fallback will be used)\n")
            append(if (battery) "Battery: unrestricted\n" else "Battery: may be restricted\n")
            append(if (running) "Dragon: running" else "Dragon: stopped")
        }
        toggle.text = if (running) "Stop dragon" else "Start dragon"
    }

    private fun toggleService() {
        if (DragonService.instance != null) {
            startService(Intent(this, DragonService::class.java).setAction(DragonService.ACTION_STOP))
        } else {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Do step 1 first.", Toast.LENGTH_SHORT).show()
                return
            }
            startForegroundService(Intent(this, DragonService::class.java))
        }
        window.decorView.postDelayed({ refresh() }, 500)
    }

    private fun openBackgroundSettings() {
        // Battery: ask for "No restrictions".
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        // HyperOS / MIUI: Autostart screen (not available on every build, so failure is fine).
        try {
            startActivity(Intent().setComponent(ComponentName(
                "com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )))
        } catch (_: Exception) {
        }
        Toast.makeText(this, "Also lock Home Dragon in the recent apps list.", Toast.LENGTH_LONG).show()
    }
}
