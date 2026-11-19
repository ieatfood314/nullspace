package ca.heeney.uplink

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver

private val Dim = Color(0xFF8A8A8A)
private val Line = Color(0xFF1F1F1F)
private val Inter = FontFamily(Font(R.font.inter_regular), Font(R.font.inter_medium, FontWeight.Medium))

class SettingsActivity : ComponentActivity() {
    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refresh++
        Reminders.rearm(this)
    }

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** Place reminders need location all the time, which Android only grants from its own page. */
    private fun askLocation() {
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) {
            askPerms.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        } else {
            askPerms.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
        }
    }
    private var refresh by mutableIntStateOf(0)
    /** the accent you pick here, so the screen changes as you pick it */
    private var accentArgb by mutableIntStateOf(Prefs.accent)
    private val Accent: Color get() = Color(accentArgb)
    /** the row whose help line is showing, by label; one at a time */
    private var helpFor by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Screen() }
    }

    private fun a11yOn(): Boolean {
        if (UplinkService.instance != null) return true
        val list = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return list.contains("$packageName/")
    }

    private fun micOn() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun open(action: String) {
        try { startActivity(Intent(action)) } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    @Composable
    private fun <T> Observable<T>.state(): State<T> {
        val s = remember { mutableStateOf(value) }
        DisposableEffect(this) {
            val off = listen { v -> runOnUiThread { s.value = v } }
            s.value = value
            onDispose { off() }
        }
        return s
    }

    @Composable
    private fun Screen() {
        val owner = LocalLifecycleOwner.current
        DisposableEffect(owner) {
            val obs = LifecycleEventObserver { _, e ->
                if (e == Lifecycle.Event.ON_RESUME) { refresh++; Sync.soon(this@SettingsActivity, minGapMs = 10_000); HubSync.soon(this@SettingsActivity) }
            }
            owner.lifecycle.addObserver(obs)
            onDispose { owner.lifecycle.removeObserver(obs) }
        }
        val engine by Engine.state.state()
        val last by Stats.last.state()
        val phase by Dictation.phase.state()
        var learning by remember { mutableStateOf(false) }
        var keyName by remember { mutableStateOf(Prefs.keyName()) }
        var open by remember { mutableStateOf("") }
        fun toggle(s: String) { open = if (open == s) "" else s }
        @Suppress("UNUSED_EXPRESSION") refresh
        LaunchedEffect(refresh) { Engine.warm(this@SettingsActivity) }

        Column(
            Modifier.fillMaxSize().background(Color.Black).systemBarsPadding().imePadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White,
                modifier = Modifier.offset(x = (-12).dp, y = (-12).dp).size(48.dp)
                    .clickable(role = Role.Button) { finish() }.padding(12.dp))
            Text("Settings", color = Color.White, fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 40.sp)
            Spacer(Modifier.height(32.dp))

            val a11y = a11yOn()
            SettingRow("Accessibility", if (a11y) "On" else null, "Turn on",
                help = "Lets Nullspace notice the key and type what you say into any app.") { open(Settings.ACTION_ACCESSIBILITY_SETTINGS) }
            SettingRow("Microphone", if (micOn()) "Allowed" else null, "Allow", help = "For dictation and voice notes.") { askMic.launch(Manifest.permission.RECORD_AUDIO) }
            ModelRow(engine, open == "model") { toggle("model") }
            SettingRow("Shortcut key", if (learning) "Press a key" else keyName, null, action = if (learning || !a11y) null else "Change",
                help = "The button that runs Nullspace. Tap Change, then press the one you want.") {
                learning = true
                UplinkService.learner = { ev ->
                    Prefs.keyCode = ev.keyCode
                    Prefs.scanCode = ev.scanCode
                    runOnUiThread { learning = false; keyName = Prefs.keyName() }
                }
            }
            if (a11y) KeyLegend(Prefs.volumeKey)
            AiRow(open == "ai") { toggle("ai") }
            if (Brain.on() || HubSync.url() != null) {
                var tidy by remember { mutableStateOf(Prefs.tidy) }
                SettingRow("Tidy dictation", if (tidy) "On" else "Off", null, action = if (tidy) "Turn off" else "Turn on",
                    help = "AI cuts repeats and false starts from what you dictate.") {
                    tidy = !tidy
                    Prefs.tidy = tidy
                }
            }
            SettingRow("Notifications", if (has(Manifest.permission.POST_NOTIFICATIONS)) "Allowed" else null, "Allow",
                help = "For reminders.") {
                askPerms.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
            val fine = has(Manifest.permission.ACCESS_FINE_LOCATION)
            SettingRow("Location", if (Reminders.locationOk(this@SettingsActivity)) "All the time" else null,
                if (fine) "Allow all the time" else "Allow", help = "For reminders tied to a place, like home.") { askLocation() }
            AccentRow(open == "accent") { toggle("accent") }
            DataRow(open == "data") { toggle("data") }
            HubRow(open == "hub") { toggle("hub") }
            SyncRow(open == "sync") { toggle("sync") }
            WordsRow(open == "words") { toggle("words") }
            var keep by remember { mutableStateOf(Prefs.keepRecordings) }
            if (keep || Sync.url() != null) {
                SettingRow("Keep recordings", if (keep) "On" else "Off", null, action = if (keep) "Turn off" else "Turn on",
                    help = "Saves your dictation audio and fixes on your Squelch hub, to train speech models.") {
                    keep = !keep
                    Prefs.keepRecordings = keep
                }
            }
            if (BuildConfig.DONATE_URL.isNotEmpty()) ShareRow()

            Spacer(Modifier.height(32.dp))
            var text by remember { mutableStateOf("") }
            BasicTextField(
                value = text, onValueChange = { text = it },
                textStyle = TextStyle(color = Color.White, fontFamily = Inter, fontSize = 18.sp),
                cursorBrush = SolidColor(Accent),
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp)
                    .border(1.dp, if (phase == Dictation.Phase.Listening) Accent else Line, RoundedCornerShape(16.dp))
                    .padding(16.dp),
                decorationBox = { inner ->
                    if (text.isEmpty()) Text("Try it here", color = Dim, fontFamily = Inter, fontSize = 18.sp)
                    inner()
                },
            )
            if (last.isNotEmpty() && Stats.lastWaitMs >= 0) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "%.2f s".format(Stats.lastWaitMs / 1000f),
                    color = Dim, fontFamily = Inter, fontSize = 13.sp,
                )
            }
            AnalyticsSection()
        }
    }

    // ---- model ----

    @Composable
    private fun ModelRow(engine: Engine.State, expanded: Boolean, onToggle: () -> Unit) {
        val dl by Download.progress.state()
        val dlKey by Download.which.state()
        val dlErr by Download.error.state()
        val cur = Models.selected(this)
        val installed = cur.installed(this)
        val status = when {
            !installed && dl != null && dlKey == cur.key -> "${((dl ?: 0f) * 100).toInt()}%"
            !installed -> null
            engine == Engine.State.Failed -> "${cur.name} · Failed"
            engine == Engine.State.Loading -> "${cur.name} · Loading"
            else -> cur.name
        }
        // Nothing downloaded yet: the list stays open so you can pick one.
        val show = expanded || !installed
        SettingRow("Speech model", status ?: "Pick one", null, action = if (!installed) null else if (expanded) "Done" else "Change",
            divider = !show, help = "Turns your voice into text, on the phone. Your audio stays here.", onClick = onToggle)
        if (!show) return
        var tick by remember { mutableIntStateOf(0) }
        @Suppress("UNUSED_EXPRESSION") tick
        for (m in Models.all(this)) {
            val here = m.installed(this)
            val busy = dl != null && dlKey == m.key
            val right = when {
                busy -> "${((dl ?: 0f) * 100).toInt()}%"
                dlErr == m.key -> Download.errorText
                m.key == cur.key && here -> "In use"
                here -> "Ready"
                else -> m.size()
            }
            Row(
                Modifier.fillMaxWidth().clickable(enabled = dl == null && (m.key != cur.key || !here)) {
                    if (here) Engine.select(this, m) else Download.start(this, m)
                    tick++
                }.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).background(if (m.key == cur.key) Accent else Color.Transparent, RoundedCornerShape(4.dp)))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(m.name, color = Color.White, fontFamily = Inter, fontSize = 16.sp)
                    Text(m.about, color = Dim, fontFamily = Inter, fontSize = 13.sp)
                }
                Spacer(Modifier.width(12.dp))
                Text(right, color = Dim, fontFamily = Inter, fontSize = 14.sp)
                if (here && m.key != cur.key && dl == null) {
                    Spacer(Modifier.width(12.dp))
                    Text("Remove", color = Accent, fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 14.sp,
                        modifier = Modifier.clickable { Models.remove(this@SettingsActivity, m); tick++ })
                }
            }
        }
        var repo by remember { mutableStateOf("") }
        Spacer(Modifier.height(4.dp))
        InputLine(repo, { repo = it }, "Hugging Face model, user/name", "Get",
            enabled = dl == null && Models.parseRepo(repo) != null) {
            Models.parseRepo(repo)?.let { Download.custom(this, it); repo = "" }
        }
        if (dlErr != null && Models.BUILT_IN.none { it.key == dlErr }) {
            Text(Download.errorText, color = Dim, fontFamily = Inter, fontSize = 13.sp)
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
    }

    // ---- the key ----

    @Composable
    private fun KeyLegend(volume: Boolean) {
        val rows = if (volume) listOf("Hold in a text box" to "Dictate", "Hold anywhere else" to "Voice note",
                "Press" to "Volume, as usual")
            else listOf("Hold" to "Dictate", "Press" to "Capture the screen",
                "Press, then hold" to "Voice note", "Double press" to "Open Nullspace")
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            for ((a, b) in rows) {
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(a, color = Dim, fontFamily = Inter, fontSize = 14.sp, modifier = Modifier.width(140.dp))
                    Text(b, color = Color.White, fontFamily = Inter, fontSize = 14.sp)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
    }

    // ---- your own AI ----

    @Composable
    private fun AiRow(expanded: Boolean, onToggle: () -> Unit) {
        var tick by remember { mutableIntStateOf(0) }
        var checking by remember { mutableStateOf(false) }
        var err by remember { mutableStateOf("") }
        @Suppress("UNUSED_EXPRESSION") tick
        val status = when {
            checking -> "Checking"
            err.isNotEmpty() -> err
            Brain.on() -> Brain.name()
            else -> null
        }
        SettingRow("AI provider", status, "Set up", action = if (expanded) "Done" else if (Brain.on()) "Change" else "Set up",
            divider = !expanded, help = "Optional. Your own AI key files your captures and tidies dictation. Only text is sent.",
            onClick = onToggle)
        if (!expanded) return
        var preset by remember { mutableStateOf(Brain.preset().key) }
        var key by remember { mutableStateOf(Prefs.llmKey) }
        var url by remember { mutableStateOf(Prefs.llmUrl) }
        var model by remember { mutableStateOf(Prefs.llmModel) }
        val groq = preset == Brain.GROQ.key
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (p in listOf(Brain.GROQ, Brain.OTHER)) {
                val on = p.key == preset
                Box(
                    Modifier.weight(1f).border(1.dp, if (on) Accent else Line, RoundedCornerShape(12.dp))
                        .clickable { preset = p.key }.padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(p.name, color = if (on) Accent else Dim, fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 16.sp)
                }
            }
        }
        if (!groq) InputLine(url, { url = it }, "Chat completions URL", null, enabled = false) {}
        InputLine(key, { key = it }, if (groq) "Groq API key" else "API key, if it needs one", null, enabled = false, secret = true) {}
        InputLine(model, { model = it }, if (groq) Brain.GROQ.model else "Model", null, enabled = false) {}
        val dirty = preset != Brain.preset().key || key.trim() != Prefs.llmKey || url.trim() != Prefs.llmUrl || model.trim() != Prefs.llmModel
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (groq) Text("Get a key", color = Accent, fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 16.sp,
                modifier = Modifier.clickable { web(Brain.GROQ.keysPage!!) })
            Spacer(Modifier.weight(1f))
            Text("Save", color = if (dirty && !checking) Accent else Dim, fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 16.sp,
                modifier = Modifier.clickable(enabled = dirty && !checking) {
                    Prefs.llmPreset = preset
                    Prefs.llmKey = key.trim()
                    Prefs.llmUrl = url.trim()
                    Prefs.llmModel = model.trim()
                    err = ""
                    if (!Brain.on()) { tick++; return@clickable }
                    checking = true
                    val app = applicationContext
                    Thread {
                        val e = Brain.check(app)
                        runOnUiThread {
                            checking = false
                            err = e.orEmpty()
                            model = Prefs.llmModel
                            tick++
                            if (e == null) HubSync.soon(app)
                        }
                    }.start()
                })
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
    }

    private fun web(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) } catch (_: Exception) {}
    }

    // ---- accent ----

    @Composable
    private fun AccentRow(expanded: Boolean, onToggle: () -> Unit) {
        SettingRow("Accent color", "#%06X".format(accentArgb and 0xFFFFFF), null, action = if (expanded) "Done" else "Change",
            divider = !expanded, help = "The color of buttons and highlights.", onClick = onToggle)
        if (!expanded) return
        fun pick(c: Int) { Prefs.accent = c; accentArgb = Prefs.accent }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            for (c in Prefs.ACCENTS) {
                val on = c == accentArgb
                Box(
                    Modifier.size(36.dp).clickable { pick(c) }.border(2.dp, if (on) Color.White else Color.Transparent, CircleShape)
                        .padding(5.dp).background(Color(c), CircleShape),
                )
            }
        }
        var hex by remember { mutableStateOf("") }
        val parsed = hex.trim().removePrefix("#").takeIf { Regex("[0-9a-fA-F]{6}").matches(it) }?.toLong(16)?.toInt()
        InputLine(hex, { hex = it }, "Any color, like #3D9BFF", "Use", enabled = parsed != null) {
            pick(parsed!! or 0xFF000000.toInt()); hex = ""
        }
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
    }

    // ---- mobile data ----

    @Composable
    private fun DataRow(expanded: Boolean, onToggle: () -> Unit) {
        var cap by remember { mutableIntStateOf(Prefs.dataCapMb) }
        @Suppress("UNUSED_EXPRESSION") cap
        SettingRow("Mobile data", Budget.text(), null, action = if (expanded) "Done" else "Change", divider = !expanded,
            help = "How much mobile data Nullspace may use a month. Wi-Fi doesn't count.", onClick = onToggle)
        if (!expanded) return
        var mb by remember { mutableStateOf(Prefs.dataCapMb.toString()) }
        val n = mb.trim().toIntOrNull()?.takeIf { it in 0..100_000 }
        InputLine(mb, { v -> mb = v.filter { it.isDigit() } }, "MB a month", "Save", enabled = n != null && n != Prefs.dataCapMb,
            keyboard = KeyboardType.Number) {
            Prefs.dataCapMb = n!!; cap = n
        }
        Text("0 keeps it to Wi-Fi.", color = Dim, fontFamily = Inter, fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
    }

    // ---- sharing, opt in ----

    @Composable
    private fun ShareRow() {
        var on by remember { mutableStateOf(Prefs.shareTraining) }
        SettingRow("Share training data", if (on) "On" else "Off", null, action = if (on) "Turn off" else "Turn on", divider = false) {
            on = !on
            Prefs.shareTraining = on
            if (on) Archive.shareSoon(this) else Archive.dropShared(this)
        }
        Text("Sends your dictation audio and the fixes you make to the project, on Wi-Fi, to train better speech models. Off unless you turn it on.",
            color = Dim, fontFamily = Inter, fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
    }

    // ---- your own hub ----

    @Composable
    private fun HubRow(expanded: Boolean, onToggle: () -> Unit) {
        val st by HubSync.status.state()
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(st) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(30_000) } }
        val status = when (st) {
            HubSync.Status.Off -> if (HubSync.url() == null) "Off" else "Not yet"
            HubSync.Status.Syncing -> "Syncing"
            HubSync.Status.Failed -> HubSync.lastError.ifEmpty { "Can't reach hub" }
            HubSync.Status.Synced -> ago(now - HubSync.lastOk)
        }
        SettingRow("Hub", status, "Set up", action = if (expanded) "Done" else if (HubSync.url() == null) "Set up" else "Change",
            divider = !expanded, help = "Optional. Your own server, to share your list with your computers.", onClick = onToggle)
        if (!expanded) return
        var url by remember { mutableStateOf(Prefs.hubUrl) }
        InputLine(url, { url = it }, "Hub address, like http://server:4645", "Save", enabled = url.trim() != Prefs.hubUrl) {
            Prefs.hubUrl = url.trim()
            if (url.isBlank()) HubSync.status.value = HubSync.Status.Off else HubSync.soon(this)
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
    }

    // ---- sync ----

    @Composable
    private fun SyncRow(expanded: Boolean, onToggle: () -> Unit) {
        val st by Sync.status.state()
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(st) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(30_000) } }
        val status = when (st) {
            Sync.Status.Off -> if (Sync.url() == null) "Off" else "Not yet"
            Sync.Status.Syncing -> "Syncing"
            Sync.Status.Failed -> "Can't reach hub"
            Sync.Status.Synced -> ago(now - Sync.lastOk)
        }
        SettingRow("Words sync", status, null, action = if (expanded) "Done" else if (Sync.url() == null) "Set up" else "Change",
            divider = !expanded, help = "Shares your custom words and stats with Squelch on your computers. Wi-Fi only.", onClick = onToggle)
        if (!expanded) return
        var url by remember { mutableStateOf(Prefs.syncUrl) }
        InputLine(url, { url = it }, "Squelch hub address", "Save", enabled = url.trim() != Prefs.syncUrl) {
            Prefs.syncUrl = url.trim()
            if (url.isBlank()) Sync.status.value = Sync.Status.Off else Sync.soon(this)
        }
        if (st == Sync.Status.Failed) Text(Sync.lastError, color = Dim, fontFamily = Inter, fontSize = 13.sp)
        Spacer(Modifier.height(if (st == Sync.Status.Failed) 16.dp else 10.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
    }

    private fun ago(ms: Long): String = when {
        ms < 60_000 -> "Synced just now"
        ms < 3_600_000 -> "Synced ${ms / 60_000} min ago"
        ms < 86_400_000 -> "Synced ${ms / 3_600_000} h ago"
        else -> "Synced ${ms / 86_400_000} d ago"
    }

    // ---- words ----

    @Composable
    private fun WordsRow(expanded: Boolean, onToggle: () -> Unit) {
        val ver by Words.changed.state()
        val words = remember(ver, refresh) { Words.load(this) }
        SettingRow("Custom words", "${words.size}", null, action = if (expanded) "Done" else "Edit", divider = !expanded,
            help = "Names and words dictation gets wrong. Add how it should be spelled and what it hears instead.",
            onClick = onToggle)
        if (!expanded) return
        var term by remember { mutableStateOf("") }
        var heard by remember { mutableStateOf("") }
        InputLine(term, { term = it }, "Word, spelled right", null, enabled = false) {}
        InputLine(heard, { heard = it }, "Heard as, comma separated", "Add", enabled = term.isNotBlank()) {
            Words.add(this, term, heard.split(','))
            term = ""; heard = ""
        }
        Spacer(Modifier.height(8.dp))
        for (w in words.asReversed()) {
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(w.term, color = Color.White, fontFamily = Inter, fontSize = 16.sp)
                    if (w.heard.isNotEmpty()) Text(w.heard.joinToString(", "), color = Dim, fontFamily = Inter, fontSize = 13.sp)
                }
                Spacer(Modifier.width(12.dp))
                Text("Remove", color = Accent, fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 14.sp,
                    modifier = Modifier.clickable { Words.remove(this@SettingsActivity, w.term) })
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
    }

    // ---- analytics ----

    @Composable
    private fun AnalyticsSection() {
        val ver by Analytics.changed.state()
        val s = remember(ver, refresh) { Analytics.summarize(Analytics.load(this)) }
        if (s.dictations == 0) return
        Spacer(Modifier.height(40.dp))
        Text("Analytics", color = Color.White, fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 22.sp)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth()) {
            Tile("%,d".format(s.words), "words")
            Tile("%.0f".format(s.wpm), "WPM")
            Tile("%.2f s".format(s.avgLat), "avg wait")
            Tile("%,d".format(s.dictations), "dictations")
        }
        Spacer(Modifier.height(16.dp))
        Text(
            if (s.recentN > 0) "Last hour: %.0f WPM · %,d words · last 10 avg %.2f s".format(s.recentWpm, s.recentWords, s.recentLat)
            else "Nothing in the last hour",
            color = Dim, fontFamily = Inter, fontSize = 13.sp,
        )
        if (s.devices.size > 1) {
            Spacer(Modifier.height(4.dp))
            Text(s.devices.joinToString(" · ") { (d, w) -> "$d %,d".format(w) }, color = Dim, fontFamily = Inter, fontSize = 13.sp)
        }
        Spacer(Modifier.height(16.dp))
        val fmt = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM")
        TableRow("Day", "Words", "Dictations", "Minutes", Dim)
        for (d in s.days) {
            TableRow(d.date.format(fmt), "%,d".format(d.words), "%,d".format(d.dictations), "%.1f".format(d.minutes), Color.White)
        }
    }

    @Composable
    private fun RowScope.Tile(value: String, caption: String) {
        Column(Modifier.weight(1f)) {
            Text(value, color = Color.White, fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 20.sp, maxLines = 1)
            Text(caption, color = Dim, fontFamily = Inter, fontSize = 12.sp, maxLines = 1)
        }
    }

    @Composable
    private fun TableRow(a: String, b: String, c: String, d: String, color: Color) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(a, color = color, fontFamily = Inter, fontSize = 14.sp, modifier = Modifier.weight(1.4f))
            for (v in listOf(b, c, d)) {
                Text(v, color = color, fontFamily = Inter, fontSize = 14.sp, modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
        }
    }

    /** a one-line field with an optional action in the accent on its right */
    @Composable
    private fun InputLine(value: String, onChange: (String) -> Unit, hint: String, action: String?, enabled: Boolean,
                          secret: Boolean = false, keyboard: KeyboardType = KeyboardType.Text, onAction: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp).border(1.dp, Line, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = value, onValueChange = onChange, singleLine = true,
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard),
                textStyle = TextStyle(color = Color.White, fontFamily = Inter, fontSize = 16.sp),
                cursorBrush = SolidColor(Accent),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (value.isEmpty()) Text(hint, color = Dim, fontFamily = Inter, fontSize = 16.sp)
                    inner()
                },
            )
            if (action != null) {
                Spacer(Modifier.width(12.dp))
                Text(action, color = if (enabled) Accent else Dim, fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 16.sp,
                    modifier = Modifier.clickable(enabled = enabled, onClick = onAction))
            }
        }
    }

    /** label on the left; a status in grey when it's done, else the action in the accent.
     *  With [help], an (i) after the label shows a line saying what the row is for. */
    @Composable
    private fun SettingRow(label: String, status: String?, fix: String?, action: String? = null, divider: Boolean = true,
                           help: String? = null, onClick: () -> Unit) {
        val tap = action ?: if (status == null) fix else null
        val showHelp = help != null && helpFor == label
        Row(
            Modifier.fillMaxWidth().then(if (tap != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(vertical = if (help != null) 4.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = Color.White, fontFamily = Inter, fontSize = 18.sp, maxLines = 1, softWrap = false)
            if (help != null) {
                Icon(Icons.Outlined.Info, if (showHelp) "Hide what $label does" else "What $label does",
                    tint = if (showHelp) Color.White else Dim,
                    modifier = Modifier.size(48.dp).clickable(role = Role.Button) { helpFor = if (showHelp) "" else label }
                        .padding(14.dp))
            } else Spacer(Modifier.width(16.dp))
            Text(status ?: "", color = Dim, fontFamily = Inter, fontSize = 16.sp, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.weight(1f))
            if (tap != null) {
                if (status != null) Spacer(Modifier.width(16.dp))
                Text(tap, color = Accent, fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 16.sp)
            }
        }
        if (showHelp) Text(help!!, color = Dim, fontFamily = Inter, fontSize = 14.sp, lineHeight = 20.sp,
            modifier = Modifier.padding(bottom = 16.dp))
        if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
    }
}
