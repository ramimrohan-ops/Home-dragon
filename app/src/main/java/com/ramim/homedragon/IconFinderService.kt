package com.ramim.homedragon

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.min

/**
 * Finds home screen icon positions. It only reads node bounds (and whether a node is
 * clickable and labelled). It never reads or stores text.
 */
class IconFinderService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var scanQueued = false
    private var lastScan = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        IconRegistry.launcherPkg = packageManager.resolveActivity(home, 0)?.activityInfo?.packageName
        IconRegistry.serviceActive = true
        IconRegistry.listener?.invoke()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        IconRegistry.serviceActive = false
        IconRegistry.icons = emptyList()
        IconRegistry.listener?.invoke()
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        val launcher = IconRegistry.launcherPkg ?: return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // Ignore system overlays that do not change what the user is looking at.
            if (pkg == "com.android.systemui" || pkg == packageName) return
            val nowHome = pkg == launcher
            if (nowHome != IconRegistry.onHome) {
                IconRegistry.onHome = nowHome
                IconRegistry.listener?.invoke()
            }
        }
        if (pkg == launcher) queueScan()
    }

    private fun queueScan() {
        if (scanQueued) return
        scanQueued = true
        val wait = (300 - (System.currentTimeMillis() - lastScan)).coerceAtLeast(60)
        handler.postDelayed({
            scanQueued = false
            lastScan = System.currentTimeMillis()
            scan()
        }, wait)
    }

    private fun scan() {
        val root = try { rootInActiveWindow } catch (e: Exception) { null } ?: return
        if (root.packageName?.toString() != IconRegistry.launcherPkg) return

        val d = resources.displayMetrics
        val minSide = 44 * d.density
        val maxSide = 140 * d.density
        val found = ArrayList<RectF>()
        val seen = HashSet<Long>()
        val b = Rect()

        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 14) return
            val cls = n.className?.toString() ?: ""
            val isWidget = cls.contains("WidgetHostView")
            val labelled = !n.text.isNullOrEmpty() || !n.contentDescription.isNullOrEmpty()
            val isIconNode = (n.isClickable || n.isLongClickable) && labelled
            if (n.isVisibleToUser && (isWidget || isIconNode)) {
                n.getBoundsInScreen(b)
                val w = b.width().toFloat()
                val h = b.height().toFloat()
                val onScreen = b.left >= 0 && b.right <= d.widthPixels && b.top >= 0 && b.bottom <= d.heightPixels
                if (isIconNode && w in minSide..maxSide && h in minSide..(maxSide * 1.3f) && h / w in 0.6f..1.6f && onScreen) {
                    val key = (b.left.toLong() shl 32) xor b.top.toLong()
                    if (seen.add(key)) {
                        // The node covers icon + label. Keep the icon part.
                        val iconSide = min(w * 0.78f, h * 0.72f)
                        val cx = b.exactCenterX()
                        val top = b.top + h * 0.05f
                        found.add(RectF(cx - iconSide / 2, top, cx + iconSide / 2, top + iconSide))
                    }
                } else if ((isWidget || isIconNode) && w > maxSide * 0.9f && w <= d.widthPixels * 0.99f &&
                    h >= minSide && h <= d.heightPixels * 0.45f && onScreen
                ) {
                    // Widget or large folder: bigger ground. The whole rectangle is kept.
                    val key = (b.left.toLong() shl 32) xor b.top.toLong()
                    if (seen.add(key)) {
                        found.add(RectF(b))
                        return   // do not treat things inside a widget as separate icons
                    }
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        try { walk(root, 0) } catch (e: Exception) { return }

        if (found.size >= 4) {
            IconRegistry.icons = found
            IconRegistry.listener?.invoke()
        }
    }
}
