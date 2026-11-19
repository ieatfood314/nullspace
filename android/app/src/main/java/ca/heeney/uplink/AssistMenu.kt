package ca.heeney.uplink

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView

/** What the phone opens as its assistant: holding power (where the phone lets that open the
 *  assistant), a swipe up from a bottom corner, or a long press on home. It has no screen of its
 *  own: it hands over to UplinkService, which shows the menu over whatever you were doing. */
class AssistActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val svc = UplinkService.instance
        if (svc == null) {
            Notice.show(this, "Turn on Nullspace in Accessibility settings")
            startActivity(android.content.Intent(this, SettingsActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } else svc.showMenu()
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}

/** For phones without a spare key: Dictate (when you're typing), Voice note and Capture, as
 *  buttons at the bottom of the screen. It doesn't take focus, so dictation lands in the field
 *  you were in. While it listens it shows the level bars and Done. */
class AssistMenu(private val svc: UplinkService) {
    private val main = Handler(Looper.getMainLooper())
    private val d = svc.resources.displayMetrics.density
    private var root: LinearLayout? = null
    private var unlisten: (() -> Unit)? = null
    private var talking = false
    private val timeout = Runnable { close() }

    val open get() = root != null

    fun show(typing: Boolean) {
        val v = build(typing)
        // A wrap_content overlay gets squeezed and wraps "Capture", so give it its own width.
        val screen = svc.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds.width()
        v.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val lp = WindowManager.LayoutParams(
            minOf(v.measuredWidth, screen - (16 * d).toInt()), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = navBarHeight() + (24 * d).toInt()
        }
        try { svc.getSystemService(WindowManager::class.java).addView(v, lp) } catch (t: Throwable) { Log.e(TAG, "menu", t); return }
        root = v
        v.alpha = 0f
        v.translationY = 24 * d
        v.animate().alpha(1f).translationY(0f).setDuration(200).setInterpolator(DecelerateInterpolator()).start()
        unlisten = Dictation.phase.listen { p -> main.post { onPhase(p) } }
        main.postDelayed(timeout, SHOW_MS)
    }

    /** Gone at once, no fade: a capture comes next and mustn't catch it. */
    fun close() {
        if (talking && Dictation.listening) Dictation.cancel()
        talking = false
        main.removeCallbacks(timeout)
        unlisten?.invoke()
        unlisten = null
        val v = root ?: return
        root = null
        try { svc.getSystemService(WindowManager::class.java).removeView(v) } catch (_: Throwable) {}
    }

    private fun talk(dictate: Boolean) {
        if (!Engine.installed(svc)) { close(); Notice.show(svc, "Open Nullspace settings to download the speech model"); return }
        main.removeCallbacks(timeout)
        if (dictate) Dictation.start(svc, svc::insert, onSaid = svc::insertSaid)
        else Dictation.start(svc, { text -> Capture.voiceNote(svc, text) })
        if (!Dictation.listening) { close(); return }
        talking = true
        val row = root ?: return
        row.removeAllViews()
        row.addView(PillView(svc).apply { phase = Dictation.Phase.Listening },
            LinearLayout.LayoutParams((132 * d).toInt(), (48 * d).toInt()))
        row.addView(button("Done", primary = true) { if (Dictation.listening) Dictation.finish(svc) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (48 * d).toInt()).apply { leftMargin = (8 * d).toInt() })
    }

    private fun onPhase(p: Dictation.Phase) {
        if (!talking) return
        val row = root ?: return
        (row.getChildAt(0) as? PillView)?.phase = p
        if (p == Dictation.Phase.Working) row.getChildAt(1)?.visibility = View.GONE
        if (p == Dictation.Phase.Idle) { talking = false; close() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun build(typing: Boolean): LinearLayout {
        val row = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val p = (8 * d).toInt()
            setPadding(p, p, p, p)
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = 32 * d
                setStroke(d.toInt().coerceAtLeast(1), 0xFF2A2A2A.toInt())
            }
            elevation = 8 * d
        }
        val items = buildList {
            if (typing) add(button("Dictate", primary = true) { talk(dictate = true) })
            add(button("Voice note", primary = !typing) { talk(dictate = false) })
            add(button("Capture", primary = false) { close(); main.postDelayed({ svc.captureNow() }, 120) })
        }
        items.forEachIndexed { i, b ->
            row.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (48 * d).toInt())
                .apply { if (i > 0) leftMargin = (8 * d).toInt() })
        }
        // Touching the app behind it means you've moved on (but not mid-sentence).
        row.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE && !talking) close()
            false
        }
        return row
    }

    private fun button(label: String, primary: Boolean, onClick: () -> Unit) = TextView(svc).apply {
        text = label
        isSingleLine = true
        textSize = 16f
        typeface = svc.resources.getFont(R.font.inter_medium)
        gravity = Gravity.CENTER
        setTextColor(if (primary) Color.BLACK else Color.WHITE)
        setPadding((20 * d).toInt(), 0, (20 * d).toInt(), 0)
        background = GradientDrawable().apply {
            cornerRadius = 24 * d
            if (primary) setColor(PillView.ACCENT) else { setColor(Color.BLACK); setStroke(d.toInt().coerceAtLeast(1), 0xFF3A3A3A.toInt()) }
        }
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun navBarHeight(): Int {
        val id = svc.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) svc.resources.getDimensionPixelSize(id) else 0
    }

    companion object {
        /** how long it waits for a choice */
        const val SHOW_MS = 8000L
    }
}
