package ca.heeney.uplink

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import kotlin.math.sin

/** The card a press leaves at the bottom of the screen: the screenshot, and a mic to say
 *  something about it. Holding the key while it's up does the same as the mic. It keeps the
 *  screenshot when it times out, when you touch the app behind it, or on another press. */
class ShotCard(private val svc: UplinkService, private val shot: Capture.Pending) {
    private val main = Handler(Looper.getMainLooper())
    private val d = svc.resources.displayMetrics.density
    private var root: View? = null
    private lateinit var mic: MicButton
    private lateinit var thumb: ImageView
    private var unlisten: (() -> Unit)? = null
    private var closed = false
    /** a dictation for this card is running (or transcribing) */
    private var talking = false
    private val timeout = Runnable { keep() }

    val open get() = !closed

    /** Show it. False if the overlay couldn't be added. */
    fun show(): Boolean {
        val v = try { build() } catch (t: Throwable) { Log.e(TAG, "shot card", t); return false }
        val wm = svc.getSystemService(WindowManager::class.java)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = navBarHeight() + (24 * d).toInt()
        }
        try { wm.addView(v, lp) } catch (t: Throwable) { Log.e(TAG, "shot card", t); return false }
        root = v
        v.alpha = 0f
        v.translationY = 24 * d
        v.animate().alpha(1f).translationY(0f).setDuration(200).setInterpolator(DecelerateInterpolator()).start()
        unlisten = Dictation.phase.listen { p -> main.post { onPhase(p) } }
        loadThumb(0)
        main.postDelayed(timeout, SHOW_MS)
        return true
    }

    // ---- the key, from UplinkService ----

    private var keyTalk = false

    fun keyDown() {
        main.removeCallbacks(timeout)
        keyTalk = false
        if (Dictation.phase.value != Dictation.Phase.Idle || !Engine.installed(svc)) return
        keyTalk = true
        talk()
    }

    fun keyUp(held: Long) {
        if (held >= UplinkService.HOLD_MS) {
            if (!Engine.installed(svc)) { Notice.show(svc, "Open Nullspace settings to download the speech model"); rearm(); return }
            if (keyTalk) Dictation.finish(svc) else rearm()
            return
        }
        // A press: done with this one (or with talking, if the mic was tapped).
        if (keyTalk) { Dictation.cancel(); talking = false }
        if (!talking) keep() else if (Dictation.listening) Dictation.finish(svc)
    }

    // ---- what it does ----

    private fun talk() {
        talking = true
        Dictation.start(svc, { text -> keep(text) })
        if (!Dictation.listening) talking = false
    }

    private fun onPhase(p: Dictation.Phase) {
        if (closed) return
        if (!talking) return
        mic.phase = p
        // Back to idle without any text (nothing heard, mic busy): give the card its time again.
        if (p == Dictation.Phase.Idle) { talking = false; rearm() }
    }

    private fun rearm() {
        if (closed) return
        main.removeCallbacks(timeout)
        if (!talking) main.postDelayed(timeout, SHOW_MS)
    }

    private fun onMic() {
        if (closed) return
        main.removeCallbacks(timeout)
        when {
            talking && Dictation.listening -> Dictation.finish(svc)
            talking -> {}
            Dictation.phase.value == Dictation.Phase.Idle -> { keyTalk = false; talk() }
        }
        if (!talking) rearm()
    }

    /** Keep the screenshot, with what was said about it if anything. */
    fun keep(text: String? = null) {
        if (closed) return
        Capture.commit(svc, shot, text)
        close()
    }

    private fun discard() {
        if (closed) return
        if (talking) Dictation.cancel()
        shot.dropped = true
        shot.bitmap = null
        close()
    }

    private fun close() {
        if (talking && Dictation.listening) Dictation.cancel()
        closed = true
        talking = false
        main.removeCallbacks(timeout)
        unlisten?.invoke()
        unlisten = null
        val v = root ?: return
        root = null
        v.animate().alpha(0f).translationY(24 * d).setDuration(160).setInterpolator(AccelerateInterpolator())
            .withEndAction { try { svc.getSystemService(WindowManager::class.java).removeView(v) } catch (_: Throwable) {} }
            .start()
    }

    // ---- the view ----

    @SuppressLint("ClickableViewAccessibility")
    private fun build(): View {
        val row = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = (12 * d).toInt()
            setPadding(p, p, (8 * d).toInt(), p)
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = 24 * d
                setStroke(d.toInt().coerceAtLeast(1), 0xFF2A2A2A.toInt())
            }
            elevation = 8 * d
        }
        val screen = svc.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        val th = (96 * d).toInt()
        val tw = (th * screen.width().toFloat() / screen.height().coerceAtLeast(1)).toInt()
        thumb = ImageView(svc).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF1B1B1D.toInt())
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, o: Outline) { o.setRoundRect(0, 0, view.width, view.height, 12 * d) }
            }
            clipToOutline = true
            contentDescription = "Open Nullspace"
            // Not mid-sentence: that would lose what you're saying.
            setOnClickListener { if (!talking) { keep(); svc.openApp() } }
        }
        row.addView(thumb, LinearLayout.LayoutParams(tw, th))
        mic = MicButton(svc).apply {
            contentDescription = "Add a voice note"
            setOnClickListener { onMic() }
        }
        row.addView(mic, LinearLayout.LayoutParams((64 * d).toInt(), (64 * d).toInt()).apply {
            leftMargin = (20 * d).toInt(); rightMargin = (12 * d).toInt()
        })
        val x = CrossButton(svc).apply {
            contentDescription = "Discard"
            setOnClickListener { discard() }
        }
        row.addView(x, LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt()).apply { gravity = Gravity.TOP })
        // Touching the app behind it means you've moved on.
        row.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE && !talking) keep()
            false
        }
        return row
    }

    private fun loadThumb(waited: Int) {
        if (closed) return
        val b = shot.bitmap
        if (b != null) {
            val w = (thumb.layoutParams.width).coerceAtLeast(1) * 2
            thumb.setImageBitmap(Bitmap.createScaledBitmap(b, w, (b.height.toLong() * w / b.width).toInt().coerceAtLeast(1), true))
        } else if (waited < 1500) main.postDelayed({ loadThumb(waited + 50) }, 50)
    }

    private fun navBarHeight(): Int {
        val id = svc.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) svc.resources.getDimensionPixelSize(id) else 0
    }

    companion object {
        /** how long the card waits for you before keeping the screenshot as it is */
        const val SHOW_MS = 4500L
    }
}

/** Accent circle with a mic; while listening, level bars; while transcribing, a white ripple. */
private class MicButton(ctx: Context) : View(ctx) {
    var phase = Dictation.Phase.Idle
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = PillView.ACCENT }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.2f * d; strokeCap = Paint.Cap.ROUND; color = Color.BLACK
    }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val levels = FloatArray(BARS)
    private val rect = RectF()
    private val path = Path()
    private var unlisten: (() -> Unit)? = null

    init { isClickable = true; isFocusable = true }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        unlisten = Dictation.level.listen { r -> post { if (phase == Dictation.Phase.Listening) push(r) } }
    }

    override fun onDetachedFromWindow() {
        unlisten?.invoke()
        super.onDetachedFromWindow()
    }

    private fun push(r: Float) {
        System.arraycopy(levels, 1, levels, 0, BARS - 1)
        levels[BARS - 1] = PillView.norm(r)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(cx, cy) - d
        if (phase == Dictation.Phase.Idle) {
            fill.color = PillView.ACCENT
            c.drawCircle(cx, cy, r, fill)
            // capsule, cradle, stem
            val u = r / 28f
            rect.set(cx - 6 * u, cy - 15 * u, cx + 6 * u, cy + 4 * u)
            c.drawRoundRect(rect, 6 * u, 6 * u, glyph)
            path.reset()
            rect.set(cx - 11 * u, cy - 9 * u, cx + 11 * u, cy + 10 * u)
            path.arcTo(rect, 0f, 180f)
            c.drawPath(path, glyph)
            c.drawLine(cx, cy + 10 * u, cx, cy + 15 * u, glyph)
            return
        }
        fill.color = Color.BLACK
        c.drawCircle(cx, cy, r, fill)
        ring.color = PillView.ACCENT
        c.drawCircle(cx, cy, r, ring)
        val bw = 3 * d
        val gap = 3.5f * d
        val total = BARS * bw + (BARS - 1) * gap
        var x = cx - total / 2
        val maxH = r * 1.1f
        val t = SystemClock.uptimeMillis() / 1000f
        for (i in 0 until BARS) {
            val v = if (phase == Dictation.Phase.Listening) { bar.color = PillView.ACCENT; levels[i] }
                else { bar.color = Color.WHITE; 0.25f + 0.25f * (1 + sin(t * 8f - i * 0.6f)) }
            val bh = maxOf(bw, v * maxH)
            rect.set(x, cy - bh / 2, x + bw, cy + bh / 2)
            c.drawRoundRect(rect, bw / 2, bw / 2, bar)
            x += bw + gap
        }
        if (phase == Dictation.Phase.Working) postInvalidateOnAnimation()
    }

    companion object { const val BARS = 5 }
}

private class CrossButton(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2 * d; strokeCap = Paint.Cap.ROUND; color = 0xFF8A8A8A.toInt()
    }

    init { isClickable = true; isFocusable = true }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val s = 6 * d
        c.drawLine(cx - s, cy - s, cx + s, cy + s, p)
        c.drawLine(cx - s, cy + s, cx + s, cy - s, p)
    }
}
