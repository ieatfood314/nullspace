package ca.heeney.uplink

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.PersistableBundle
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction

/** Holds the trigger key, shows the pill while listening, and puts the
 *  transcript into whatever text field has focus. */
class UplinkService : AccessibilityService() {
    private var pill: PillView? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var unlisten: (() -> Unit)? = null

    override fun onServiceConnected() {
        instance = this
        Engine.warm(this)
        unlisten = Dictation.phase.listen { p -> main.post { showPhase(p) } }
        Log.i(TAG, "accessibility service connected")
    }

    override fun onDestroy() {
        unlisten?.invoke()
        card?.keep()
        hidePill()
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // The model is most of this process; let it go before Android has to kill something.
        // (UI_HIDDEN and BACKGROUND, in between, only mean the settings screen closed.)
        if (level == TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= TRIM_MEMORY_MODERATE) Engine.unload()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val w = watch ?: return
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ->
                if (event.packageName?.toString() == w.pkg && !snapPending) {
                    snapPending = true
                    main.postDelayed(::snapshot, 100)
                }
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                val src = event.source ?: return
                if (src != watchNode && src.isEditable || event.packageName?.toString() != w.pkg) endWatch("focus")
            }
        }
    }
    override fun onInterrupt() {}

    public override fun onKeyEvent(ev: KeyEvent): Boolean {
        learner?.let { learn ->
            if (ev.action == KeyEvent.ACTION_UP) { learner = null; learn(ev) }
            return true
        }
        if (!Prefs.isTrigger(ev)) return false
        if (Prefs.volumeKey) return volumeKey(ev)
        when (ev.action) {
            KeyEvent.ACTION_DOWN -> if (ev.repeatCount == 0) down()
            KeyEvent.ACTION_UP -> up()
        }
        return true
    }

    // ---- the key ----
    //   hold                  dictate into the field you're in (Squelch)
    //   press                 capture the screen; a card shows it, and holding the key
    //                         (or tapping its mic) while it's up adds a voice note to it
    //   press, then hold      voice note
    //   double press          open Uplink
    // Listening starts on every key down, so neither hold loses its first word; a press that
    // turns out short just drops the audio.

    private var presses = 0
    private var downAt = 0L
    private var shot: Capture.Pending? = null
    private var card: ShotCard? = null
    /** this press went to the card */
    private var toCard = false
    private val single = Runnable {
        presses = 0
        shot?.let { p ->
            val c = ShotCard(this, p)
            if (c.show()) card = c
            else { Capture.commit(this, p); Notice.show(this, "Captured") }
        }
        shot = null
    }

    private fun down() {
        main.removeCallbacks(single)
        downAt = android.os.SystemClock.uptimeMillis()
        card?.takeIf { it.open }?.let { toCard = true; it.keyDown(); return }
        card = null
        if (Dictation.listening) Dictation.cancel()
        endWatch("next")
        presses = if (presses == 1) 2 else 1
        // Without a model only the presses work; a hold says so in up().
        if (!Engine.installed(this)) return
        if (presses == 1) Dictation.start(this, ::insert, onSaid = ::insertSaid)
        else Dictation.start(this, { text -> Capture.voiceNote(this, text) })
    }

    private fun up() {
        val held = android.os.SystemClock.uptimeMillis() - downAt
        if (toCard) {
            toCard = false
            card?.keyUp(held)
            // A press that closed the card counts as the first of a double press, so a second
            // one opens Uplink rather than taking another screenshot.
            if (held < HOLD_MS && card?.open == false) { card = null; presses = 1; main.postDelayed(single, GAP_MS) }
            return
        }
        if (held >= HOLD_MS) {
            // A hold: dictation on the first press, a voice note on the second.
            shot?.dropped = true
            shot = null
            presses = 0
            if (!Engine.installed(this)) Notice.show(this, "Open Nullspace settings to download the speech model")
            Dictation.finish(this)
            return
        }
        Dictation.cancel()
        hidePill()
        if (presses == 1) {
            shot = try { Capture.grab(this) } catch (t: Throwable) { Log.e(TAG, "grab", t); null }
            main.postDelayed(single, GAP_MS)
        } else {
            shot?.dropped = true
            shot = null
            presses = 0
            openApp()
        }
    }

    // ---- a volume key as the trigger ----
    //   press                 changes the volume, as always
    //   hold                  dictate into the field you're in, or a voice note when there's none
    // Phones with no spare key only have this one. A press can't be handed back to Android once
    // it's taken, so a short one sets the volume itself. The mic waits until the press is long
    // enough to be a hold, so changing the volume doesn't flash the mic dot. In a call, with the
    // screen off, or before the model is downloaded, it's just a volume key.

    /** this press went straight through to Android */
    private var volPassing = false
    private val volListen = Runnable {
        val typing = focusedField() != null
        Log.i(TAG, "volume key held: ${if (typing) "dictation" else "voice note"}")
        if (typing) Dictation.start(this, ::insert, onSaid = ::insertSaid)
        else Dictation.start(this, { text -> Capture.voiceNote(this, text) })
    }

    private fun volumeKey(ev: KeyEvent): Boolean {
        when (ev.action) {
            KeyEvent.ACTION_DOWN -> {
                if (ev.repeatCount > 0) return !volPassing
                volPassing = !volumeIsOurs()
                if (volPassing) return false
                downAt = android.os.SystemClock.uptimeMillis()
                card?.takeIf { it.open }?.keep()
                card = null
                if (Dictation.listening) Dictation.cancel()
                endWatch("next")
                main.postDelayed(volListen, VOL_LISTEN_MS)
            }
            KeyEvent.ACTION_UP -> {
                if (volPassing) return false
                main.removeCallbacks(volListen)
                if (android.os.SystemClock.uptimeMillis() - downAt >= HOLD_MS) {
                    Dictation.finish(this)
                } else {
                    Dictation.cancel()
                    hidePill()
                    val dir = if (ev.keyCode == KeyEvent.KEYCODE_VOLUME_UP) android.media.AudioManager.ADJUST_RAISE
                        else android.media.AudioManager.ADJUST_LOWER
                    // The same call the system makes for a volume key nobody took: it picks the
                    // stream that's playing, or a cast session's volume.
                    getSystemService(android.media.AudioManager::class.java).adjustSuggestedStreamVolume(
                        dir, android.media.AudioManager.USE_DEFAULT_STREAM_TYPE, android.media.AudioManager.FLAG_SHOW_UI)
                }
            }
        }
        return true
    }

    private fun volumeIsOurs(): Boolean {
        val power = getSystemService(android.os.PowerManager::class.java)
        val audio = getSystemService(android.media.AudioManager::class.java)
        return power.isInteractive && audio.mode == android.media.AudioManager.MODE_NORMAL && Engine.installed(this)
    }

    fun openApp() {
        try {
            startActivity(android.content.Intent(this, MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        } catch (t: Throwable) { Log.e(TAG, "open", t) }
    }

    // ---- the pill ----

    private fun showPhase(p: Dictation.Phase) {
        // The card's mic shows it instead.
        if (card?.open == true) { hidePill(); return }
        when (p) {
            Dictation.Phase.Idle -> hidePill()
            // Only once the press is a hold: a short press is a screenshot, which mustn't catch the pill.
            Dictation.Phase.Listening -> main.postDelayed({
                if (Dictation.phase.value == Dictation.Phase.Listening && card?.open != true) { showPill(); pill?.phase = Dictation.Phase.Listening }
            }, HOLD_MS)
            else -> { showPill(); pill?.phase = p }
        }
    }

    fun showPill() {
        if (pill != null) return
        val wm = getSystemService(WindowManager::class.java)
        val v = PillView(this)
        val d = resources.displayMetrics.density
        val lp = WindowManager.LayoutParams(
            (132 * d).toInt(), (40 * d).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = statusBarHeight() + (8 * d).toInt()
        }
        try { wm.addView(v, lp); pill = v } catch (t: Throwable) { Log.e(TAG, "pill", t) }
    }

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    fun hidePill() {
        val v = pill ?: return
        pill = null
        try { getSystemService(WindowManager::class.java).removeView(v) } catch (_: Throwable) {}
    }

    // ---- typing it in ----

    private fun focusedField(): AccessibilityNodeInfo? {
        // Compose (and some other toolkits) report the host view as the input focus, with
        // the real text field as a virtual child, so look underneath it too.
        findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { f ->
            if (f.isEditable) return f
            focusedEditableIn(f)?.let { return it }
        }
        rootInActiveWindow?.let { r -> focusedEditableIn(r)?.let { return it } }
        for (w in windows) {
            val r = w.root ?: continue
            focusedEditableIn(r)?.let { return it }
        }
        return null
    }

    private fun focusedEditableIn(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue += root
        var seen = 0
        while (queue.isNotEmpty() && seen++ < 2000) {
            val n = queue.removeFirst()
            if (n.isEditable && n.isFocused) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue += it }
        }
        return null
    }

    /** A finished dictation: guess its start if it was cut off, tidy it if it rambles, then put
     *  it in. Both go to your own LLM if you set one up, else your hub's, else are skipped. */
    fun insertSaid(said: Said) {
        val hub = HubSync.url()
        val own = Brain.on()
        val wantTidy = Prefs.tidy && said.text.split(Regex("\\s+")).count { it.isNotEmpty() } >= Brain.TIDY_MIN_WORDS
        if ((!said.cut && !wantTidy) || (!own && hub == null) || !Brain.canCall(this, made = false)) {
            insert(said.text, said); return
        }
        val before = if (!said.cut) "" else focusedField()?.let { n ->
            val t = if (n.isShowingHintText || n.isPassword) "" else n.text?.toString().orEmpty()
            val s = n.textSelectionStart
            if (s in 0..t.length) t.substring(maxOf(0, s - 300), s) else t.takeLast(300)
        }.orEmpty()
        val app = applicationContext
        Thread({
            fun ask(path: String, body: org.json.JSONObject): String? = try {
                val res = org.json.JSONObject(String(HubSync.post(app, "$hub/$path", body.toString().toByteArray(), Net.metered(app), 5_000)))
                res.optString("text").takeIf { !res.isNull("text") && it.isNotBlank() }
            } catch (t: Throwable) { Log.w(TAG, "$path: $t"); null }

            var text = said.text
            val guessed = if (!said.cut) null
                else if (own) Brain.guessStart(app, text, before)
                else ask("api/guess-start", org.json.JSONObject().put("text", text).put("before", before))
            if (guessed != null) { Log.i(TAG, "start was cut off, guessed it"); text = guessed }
            val tidied = if (!wantTidy) null
                else if (own) Brain.tidy(app, text)
                else ask("api/tidy", org.json.JSONObject().put("text", text))
            if (tidied != null) { Log.i(TAG, "tidied ${text.length} -> ${tidied.length} chars"); text = tidied }
            val out = if (guessed == null && tidied == null) said
                else Said(text, said.id, said.ts, said.cut, said.heard, guess = guessed, tidied = tidied != null)
            main.post { insert(out.text, out) }
        }, "polish").start()
    }

    fun insert(text: String) = insert(text, null)

    fun insert(text: String, said: Said?) {
        val node = focusedField()
        if (node == null) {
            copy(text)
            Notice.show(this, "Copied")
            return
        }
        val (current, exact) = readField(node)
        var s = node.textSelectionStart
        var e = node.textSelectionEnd
        if (s < 0 || e < 0 || s > current.length || e > current.length) { s = current.length; e = s }
        if (s > e) s = e.also { e = s }
        val before = current.substring(0, s)
        val after = current.substring(e)
        val piece = spaced(text, current, s, e, exact)

        // Setting the text directly leaves the clipboard alone, so try that first. Some
        // fields (Compose, web) accept it and then ignore it, so wait to see it land
        // before moving the cursor, and paste instead if it never does.
        val canPaste = node.actionList.contains(AccessibilityAction.ACTION_PASTE)
        val wanted = before + piece + after
        val setOk = !node.isPassword && !richWeb(node) && !forcePaste && node.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, wanted) },
        )
        // Once it's in, watch what you do to it (not in password fields).
        val watchIt = { if (said != null && !node.isPassword) main.postDelayed({ beginWatch(said, piece) }, 300) }
        if (!setOk) { if (paste(node, piece, canPaste)) watchIt(); return }
        val pos = before.length + piece.length
        val deadline = android.os.SystemClock.uptimeMillis() + 600
        fun check() {
            node.refresh()
            val now = node.text?.toString().orEmpty()
            when {
                now == wanted || (now != current && now.contains(piece.trim())) -> {
                    if (pos <= now.length) node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, pos)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, pos)
                    })
                    watchIt()
                }
                android.os.SystemClock.uptimeMillis() < deadline -> main.postDelayed(::check, 25)
                else -> {
                    Log.i(TAG, "set text didn't stick in ${node.className}, pasting")
                    if (paste(node, piece, canPaste)) watchIt()
                }
            }
        }
        check()
    }

    /** True if it went into the field. */
    private fun paste(node: AccessibilityNodeInfo, piece: String, canPaste: Boolean): Boolean {
        copy(piece)
        if (canPaste && node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return true
        Notice.show(this, "Copied")
        return false
    }

    // ---- what you do to it after ----

    private var watch: EditWatch? = null
    private var watchNode: AccessibilityNodeInfo? = null
    private var snapPending = false
    private val tickWatch = object : Runnable {
        override fun run() {
            val w = watch ?: return
            val now = System.currentTimeMillis()
            if (now - w.changed > EDIT_IDLE_MS || now - w.started > EDIT_MAX_MS) { endWatch("idle"); return }
            snapshot()
            if (watch != null) main.postDelayed(this, 2000)
        }
    }

    private fun fieldText(n: AccessibilityNodeInfo): String =
        if (n.isShowingHintText) "" else n.text?.toString().orEmpty()

    /** A field's text for placing a dictation in it, and whether its cursor counts in it
     *  exactly (see cursorSpots). */
    private fun readField(n: AccessibilityNodeInfo): Pair<String, Boolean> {
        if (n.isShowingHintText || n.isPassword) return "" to true
        val t = n.text?.toString().orEmpty()
        if (!richWeb(n)) return t to true
        leafText(n)?.let { return it to true }
        return t to !t.contains('\n')
    }

    /** A web editor that isn't a plain <input> (contenteditable: Obsidian, Gmail, Docs...). A
     *  whole-text replace would flatten it to what's on screen, dropping the markup or formatting
     *  it hides, so it gets pasted into. Chrome gives only <input>s an input type, and some
     *  editors (Obsidian's) call themselves a textField too. */
    private fun richWeb(n: AccessibilityNodeInfo): Boolean {
        val role = n.extras?.getString("AccessibilityNodeInfo.chromeRole") ?: return false
        return n.inputType == InputType.TYPE_NULL || role !in PLAIN_WEB_ROLES
    }

    /** Chrome counts a web editor's cursor in the text of the pieces inside it, while the text it
     *  gives for the whole editor adds line breaks the cursor doesn't count, at each line's edge
     *  and around Obsidian's links. So read the pieces. Null if there are none (see cursorSpots)
     *  or too many. */
    private fun leafText(root: AccessibilityNodeInfo): String? {
        if (root.childCount == 0) return null
        val out = StringBuilder()
        var seen = 0
        fun walk(n: AccessibilityNodeInfo): Boolean {
            if (++seen > 3000) return false
            if (n.childCount == 0) { n.text?.let { out.append(it) }; return true }
            for (i in 0 until n.childCount) { val c = n.getChild(i) ?: continue; if (!walk(c)) return false }
            return true
        }
        return if (walk(root)) out.toString() else null
    }

    private fun beginWatch(said: Said, piece: String) {
        endWatch("next")
        if (!Prefs.archiving) return
        val node = focusedField() ?: return
        val t = fieldText(node)
        val span = EditWatch.locate(t, piece, node.textSelectionEnd) ?: EditWatch.locate(t, piece, -1) ?: return
        watch = EditWatch(said, node.packageName?.toString().orEmpty(), t, span.first, span.second)
        watchNode = node
        main.postDelayed(tickWatch, 2000)
    }

    private fun snapshot() {
        snapPending = false
        val w = watch ?: return
        val n = watchNode ?: return
        if (!n.refresh()) { endWatch("gone"); return }
        w.update(fieldText(n))?.let { endWatch(it) }
    }

    private fun endWatch(why: String) {
        val w = watch ?: return
        if (why != "gone" && why != "sent" && why != "deleted") {
            watchNode?.let { if (it.refresh()) w.update(fieldText(it)) }  // the latest, before letting go
        }
        watch = null
        watchNode = null
        main.removeCallbacks(tickWatch)
        Archive.edited(this, w.record(why))
    }

    private fun copy(text: String) {
        val clip = ClipData.newPlainText("Nullspace", text)
        // Keeps the "copied" preview from flashing the text on screen.
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }

    companion object {
        @Volatile var instance: UplinkService? = null
            private set
        /** Set by the key picker: the next key press goes here instead. */
        @Volatile var learner: ((KeyEvent) -> Unit)? = null
        @Volatile var forcePaste = false
        private val PLAIN_WEB_ROLES = setOf("textField", "searchBox", "textFieldWithComboBox")
        /** a press held this long is a hold (same as Dictation's tap cut-off) */
        const val HOLD_MS = 350L
        /** how long after a press a second one still makes it a double */
        const val GAP_MS = 380L
        /** how long a volume key is down before the mic starts (most presses are over by then) */
        const val VOL_LISTEN_MS = 180L
        /** a dictation untouched this long is done with */
        const val EDIT_IDLE_MS = 120_000L
        const val EDIT_MAX_MS = 600_000L
    }
}

/** The dictation with the spaces it needs to sit between t[0, s) and t[e, end). Where the cursor
 *  could be in a few places (see cursorSpots), a space too many beats words run together. */
internal fun spaced(text: String, t: String, s: Int, e: Int, exact: Boolean): String {
    var piece = text
    if (cursorSpots(t, s, exact).any { it > 0 && !t[it - 1].isWhitespace() }) piece = " $piece"
    if (cursorSpots(t, e, exact).any { it < t.length && !t[it].isWhitespace() && t[it] !in ".,!?;:" }) piece = "$piece "
    return piece
}

/** Where cursor position x could be in a field's text: x, except in a web editor Chrome gives as
 *  one piece of text (Obsidian's, for a note without links). That text has a line break at each
 *  line's edge, which the cursor doesn't count, and one for each empty line, which it does, and
 *  the two look the same. So the cursor is somewhere from x (every break counted) to past the
 *  first x characters that aren't breaks (none counted). */
internal fun cursorSpots(t: String, x: Int, exact: Boolean): List<Int> {
    if (exact) return listOf(x)
    var i = 0
    var seen = 0
    while (i < t.length && seen < x) { if (t[i] != '\n') seen++; i++ }
    return listOf(x, i).distinct()
}
