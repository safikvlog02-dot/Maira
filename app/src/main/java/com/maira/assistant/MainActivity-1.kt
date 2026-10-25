package com.maira.assistant

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.util.Xml
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    companion object {
        const val DEFAULT_MODEL = "gemini-3.8-flash"
        const val DEFAULT_MODEL2 = "gemini-3.8-flash-lite"
        const val ERR_PREFIX = "দুঃখিত, উত্তর আনতে সমস্যা হয়েছে: "
        const val DEFAULT_TTS_MODEL = "gemini-3.8-flash-tts"
        const val REQ_MIC = 1
        const val REQ_CAL = 2
        const val REQ_LOC = 3
        const val REQ_BT = 4
    }

    private lateinit var prefs: SharedPreferences

    // পর্দা
    private lateinit var pagesHost: FrameLayout
    private lateinit var homePage: View
    private lateinit var chatPage: View
    private lateinit var toolsPage: View
    private lateinit var profilePage: View
    private lateinit var navItems: List<TextView>

    // হোম
    private lateinit var avatar: AvatarView
    private lateinit var wave: WaveView
    private lateinit var orb: OrbView
    private lateinit var orbLabel: TextView
    private lateinit var bubbleTv: TextView
    private lateinit var recentBox: LinearLayout
    private lateinit var weatherBox: LinearLayout
    private lateinit var scheduleBox: LinearLayout
    private lateinit var devicesBox: LinearLayout
    private lateinit var insightBox: LinearLayout
    private lateinit var trendBox: LinearLayout

    // চ্যাট
    private lateinit var chatBox: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var input: EditText
    private lateinit var chatStatus: TextView

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private val history = JSONArray()

    @Volatile
    private var speakGen = 0
    private var todayEvents: String = ""

    // ------------------------------------------------------------ লাইফসাইকেল

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("maira", Context.MODE_PRIVATE)

        val main = LinearLayout(this)
        main.orientation = LinearLayout.VERTICAL
        main.fitsSystemWindows = true
        val bg = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xFF05030F.toInt(), 0xFF0A0A2E.toInt(), 0xFF05030F.toInt())
        )
        main.background = bg

        pagesHost = FrameLayout(this)
        main.addView(pagesHost, LinearLayout.LayoutParams(-1, 0, 1f))
        main.addView(buildNav(), LinearLayout.LayoutParams(-1, -2))
        setContentView(main)

        homePage = buildHome()
        chatPage = buildChat()
        toolsPage = buildTools()
        profilePage = buildProfile()
        for (v in listOf(homePage, chatPage, toolsPage, profilePage)) {
            pagesHost.addView(v, FrameLayout.LayoutParams(-1, -1))
        }

        avatar.animationsOn = prefs.getBoolean("anim", true)
        tts = TextToSpeech(this, this)

        loadHistory()
        if (history.length() == 0) {
            addMsg("maira", greetingLine() + " কীভাবে সাহায্য করতে পারি?")
        }
        refreshRecent()
        showPage(0)
        setStatus()
        loadAll()
    }

    override fun onResume() {
        super.onResume()
        if (::weatherBox.isInitialized) loadAll()
    }

    override fun onDestroy() {
        speakGen++
        tts?.stop()
        tts?.shutdown()
        recognizer?.destroy()
        super.onDestroy()
    }

    override fun onStop() {
        if (listening) stopListening()
        super.onStop()
    }

    // ------------------------------------------------------------ UI সহায়ক

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(text: String, sp: Float, color: Int, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = sp
        t.setTextColor(color)
        if (bold) t.setTypeface(t.typeface, Typeface.BOLD)
        return t
    }

    private fun glass(radius: Int = 18, fill: Int = 0xCC0B1030.toInt(), stroke: Int = 0x554A6BFF): GradientDrawable {
        val g = GradientDrawable()
        g.cornerRadius = dp(radius).toFloat()
        g.setColor(fill)
        g.setStroke(dp(1), stroke)
        return g
    }

    private fun card(parent: LinearLayout, title: String): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.background = glass()
        c.setPadding(dp(14), dp(12), dp(14), dp(12))
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.setMargins(0, dp(6), 0, dp(6))
        if (title.isNotEmpty()) c.addView(tv(title, 12f, 0xFF8FA8FF.toInt(), true))
        parent.addView(c, lp)
        return c
    }

    private fun smallBtn(text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        return b
    }

    private fun row(left: String, mid: String, right: String): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.setPadding(0, dp(6), 0, dp(6))
        r.addView(tv(left, 14f, 0xFFB9C6FF.toInt()), LinearLayout.LayoutParams(dp(56), -2))
        r.addView(tv(mid, 14f, Color.WHITE), LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(tv(right, 12f, 0xFF8FA8FF.toInt()))
        return r
    }

    // ------------------------------------------------------------ নিচের মেনু ও পর্দা

    private fun buildNav(): View {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.background = glass(28, 0xEE0B1030.toInt(), 0x663A5BFF)
        bar.setPadding(dp(6), dp(8), dp(6), dp(8))
        val labels = listOf("🏠\nHome", "💬\nChat", "AI", "▦\nTools", "👤\nProfile")
        val items = ArrayList<TextView>()
        for ((i, l) in labels.withIndex()) {
            val t = tv(l, 12f, 0xFF9FB0E8.toInt())
            t.gravity = Gravity.CENTER
            if (i == 2) {
                t.textSize = 22f
                t.setTypeface(t.typeface, Typeface.BOLD)
                t.setTextColor(Color.WHITE)
                val g = GradientDrawable()
                g.shape = GradientDrawable.OVAL
                g.setColor(0xFF1A2A7A.toInt())
                g.setStroke(dp(3), 0xFF4D8DFF.toInt())
                t.background = g
                val lp = LinearLayout.LayoutParams(dp(64), dp(64))
                lp.setMargins(dp(4), 0, dp(4), 0)
                bar.addView(t, lp)
                t.setOnClickListener {
                    showPage(0)
                    toggleListening()
                }
            } else {
                bar.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
                t.setOnClickListener { showPage(if (i < 2) i else i - 1) }
            }
            items.add(t)
        }
        navItems = items
        val wrap = LinearLayout(this)
        wrap.setPadding(dp(10), dp(4), dp(10), dp(8))
        wrap.addView(bar, LinearLayout.LayoutParams(-1, -2))
        return wrap
    }

    /** 0=Home 1=Chat 2=Tools 3=Profile */
    private fun showPage(i: Int) {
        val pages = listOf(homePage, chatPage, toolsPage, profilePage)
        for ((k, p) in pages.withIndex()) p.visibility = if (k == i) View.VISIBLE else View.GONE
        val navIndex = intArrayOf(0, 1, 3, 4)
        for ((k, t) in navItems.withIndex()) {
            if (k == 2) continue
            t.setTextColor(if (k == navIndex[i]) 0xFF7FA6FF.toInt() else 0xFF9FB0E8.toInt())
        }
        if (i == 1) chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ------------------------------------------------------------ হোম পর্দা

    private fun buildHome(): View {
        val sv = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(12), dp(6), dp(12), dp(16))
        sv.addView(col)

        val head = tv("AI ASSISTANT", 12f, 0xFF6F8CFF.toInt(), true)
        head.gravity = Gravity.CENTER
        col.addView(head, LinearLayout.LayoutParams(-1, -2))

        avatar = AvatarView(this)
        col.addView(avatar, LinearLayout.LayoutParams(-1, dp(340)))

        bubbleTv = tv("", 15f, Color.WHITE)
        bubbleTv.background = glass(16, 0xCC10205A.toInt(), 0x884D8DFF.toInt())
        bubbleTv.setPadding(dp(14), dp(10), dp(14), dp(10))
        col.addView(bubbleTv, LinearLayout.LayoutParams(-1, -2))

        // শুভেচ্ছা
        val g = card(col, "")
        val name = prefs.getString("name", "") ?: ""
        g.addView(tv(if (name.isBlank()) "হ্যালো!" else "হ্যালো, $name!", 24f, Color.WHITE, true))
        g.addView(tv("আজ কী করতে চান?", 17f, 0xFF8FA8FF.toInt()))
        wave = WaveView(this)
        g.addView(wave, LinearLayout.LayoutParams(-1, dp(36)))

        // মাইক্রোফোন
        orb = OrbView(this)
        orb.setOnClickListener { toggleListening() }
        val orbLp = LinearLayout.LayoutParams(dp(150), dp(150))
        orbLp.gravity = Gravity.CENTER_HORIZONTAL
        orbLp.setMargins(0, dp(10), 0, 0)
        col.addView(orb, orbLp)
        orbLabel = tv("", 14f, 0xFFB9A4FF.toInt())
        orbLabel.gravity = Gravity.CENTER
        col.addView(orbLabel, LinearLayout.LayoutParams(-1, -2))

        // AI Tools
        val tools = card(col, "AI TOOLS")
        addToolGrid(tools)

        // চ্যাট
        val chat = card(col, "RECENT CHAT")
        recentBox = LinearLayout(this)
        recentBox.orientation = LinearLayout.VERTICAL
        chat.addView(recentBox)
        chat.addView(smallBtn("পুরো চ্যাট খুলুন") { showPage(1) })

        // আবহাওয়া
        val w = card(col, "WEATHER")
        weatherBox = LinearLayout(this)
        weatherBox.orientation = LinearLayout.VERTICAL
        w.addView(weatherBox)

        // ইনসাইট
        val ins = card(col, "AI INSIGHTS")
        insightBox = LinearLayout(this)
        insightBox.orientation = LinearLayout.VERTICAL
        ins.addView(insightBox)

        // আজকের সূচি
        val sc = card(col, "TODAY'S SCHEDULE")
        scheduleBox = LinearLayout(this)
        scheduleBox.orientation = LinearLayout.VERTICAL
        sc.addView(scheduleBox)

        // শর্টকাট
        val sh = card(col, "SMART SHORTCUTS")
        addShortcuts(sh)

        // ডিভাইস
        val dv = card(col, "CONNECTED DEVICES")
        devicesBox = LinearLayout(this)
        devicesBox.orientation = LinearLayout.VERTICAL
        dv.addView(devicesBox)

        // ট্রেন্ডিং
        val tr = card(col, "TRENDING FOR YOU")
        trendBox = LinearLayout(this)
        trendBox.orientation = LinearLayout.VERTICAL
        tr.addView(trendBox)

        return sv
    }

    private val toolList = listOf(
        Triple("📝", "Summarize", "সারাংশ করো: "),
        Triple("🌐", "Translate", "এটা অনুবাদ করো (বাংলা, হিন্দি, ইংরেজি): "),
        Triple("✍", "Write", "আমার জন্য লিখে দাও: "),
        Triple("📊", "Analyze", "বিশ্লেষণ করো: "),
        Triple("🎨", "Design", "এর জন্য ডিজাইনের আইডিয়া দাও: "),
        Triple("</>", "Code", "কোড লিখে দাও: ")
    )

    private fun addToolGrid(parent: LinearLayout) {
        for (r in 0 until 2) {
            val line = LinearLayout(this)
            line.orientation = LinearLayout.HORIZONTAL
            for (c in 0 until 3) {
                val (icon, label, prefix) = toolList[r * 3 + c]
                val cell = LinearLayout(this)
                cell.orientation = LinearLayout.VERTICAL
                cell.gravity = Gravity.CENTER
                cell.background = glass(14, 0x88142060.toInt(), 0x444D6BFF)
                cell.setPadding(0, dp(10), 0, dp(10))
                val ic = tv(icon, 22f, Color.WHITE)
                ic.gravity = Gravity.CENTER
                val lb = tv(label, 11f, 0xFFB9C6FF.toInt())
                lb.gravity = Gravity.CENTER
                cell.addView(ic)
                cell.addView(lb)
                cell.setOnClickListener { prefill(prefix) }
                val lp = LinearLayout.LayoutParams(0, -2, 1f)
                lp.setMargins(dp(4), dp(4), dp(4), dp(4))
                line.addView(cell, lp)
            }
            parent.addView(line)
        }
    }

    private fun addShortcuts(parent: LinearLayout) {
        val items = listOf(
            Triple("Meeting Summary", "মিটিংয়ের সারাংশ করুন", "এই মিটিংয়ের সারাংশ ও করণীয় লিখে দাও: "),
            Triple("Email Assistant", "প্রফেশনাল ইমেইল লিখুন", "একটা প্রফেশনাল ইমেইল লিখে দাও: "),
            Triple("Study Helper", "যেকোনো বিষয় বুঝিয়ে দিন", "এটা সহজ করে বুঝিয়ে দাও: "),
            Triple("Plan My Day", "দিনের পরিকল্পনা", "")
        )
        for ((title, sub, prefix) in items) {
            val b = LinearLayout(this)
            b.orientation = LinearLayout.VERTICAL
            b.background = glass(12, 0x88142060.toInt(), 0x444D6BFF)
            b.setPadding(dp(12), dp(8), dp(12), dp(8))
            b.addView(tv(title, 15f, Color.WHITE, true))
            b.addView(tv(sub, 12f, 0xFF8FA8FF.toInt()))
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.setMargins(0, dp(4), 0, dp(4))
            b.setOnClickListener {
                if (prefix.isEmpty()) {
                    showPage(1)
                    handleUser(
                        "আজকের আমার ইভেন্ট: " + (if (todayEvents.isBlank()) "কিছু নেই" else todayEvents) +
                            "। এর ভিত্তিতে দিনের একটা সংক্ষিপ্ত পরিকল্পনা দাও।"
                    )
                } else prefill(prefix)
            }
            parent.addView(b, lp)
        }
    }

    private fun prefill(prefix: String) {
        showPage(1)
        input.setText(prefix)
        input.setSelection(input.text.length)
        input.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    // ------------------------------------------------------------ চ্যাট, টুলস, প্রোফাইল পর্দা

    private fun buildChat(): View {
        val page = LinearLayout(this)
        page.orientation = LinearLayout.VERTICAL
        page.setPadding(dp(12), dp(6), dp(12), dp(6))
        page.addView(tv("Chat", 20f, 0xFFC9B8FF.toInt(), true))

        chatScroll = ScrollView(this)
        chatBox = LinearLayout(this)
        chatBox.orientation = LinearLayout.VERTICAL
        chatScroll.addView(chatBox)
        page.addView(chatScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        chatStatus = tv("", 13f, 0xFF8FA8FF.toInt())
        page.addView(chatStatus)

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        input = EditText(this)
        input.hint = "এখানে লিখুন…"
        input.setTextColor(Color.WHITE)
        input.setHintTextColor(0xFF7080A8.toInt())
        input.imeOptions = EditorInfo.IME_ACTION_SEND
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        input.maxLines = 4
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                send()
                true
            } else false
        }
        bar.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(smallBtn("পাঠান") { send() })
        bar.addView(smallBtn("🎤") { toggleListening() })
        page.addView(bar)
        return page
    }

    private fun buildTools(): View {
        val sv = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(12), dp(6), dp(12), dp(16))
        sv.addView(col)
        col.addView(tv("Tools", 20f, 0xFFC9B8FF.toInt(), true))
        val t = card(col, "AI TOOLS")
        addToolGrid(t)
        val s = card(col, "SMART SHORTCUTS")
        addShortcuts(s)
        val h = card(col, "ফোনে যা বলতে পারেন")
        h.addView(
            tv(
                "• \"ইউটিউব খোলো\", \"হোয়াটসঅ্যাপ খোলো\"\n• \"৫ মিনিটের টাইমার দাও\"\n• \"এখন কয়টা বাজে\", \"আজ কত তারিখ\"\n• অন্য যেকোনো প্রশ্ন বা লেখার কাজ",
                14f, Color.WHITE
            )
        )
        return sv
    }

    private fun buildProfile(): View {
        val sv = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(12), dp(6), dp(12), dp(16))
        sv.addView(col)
        col.addView(tv("Profile", 20f, 0xFFC9B8FF.toInt(), true))
        val c = card(col, "SETTINGS")
        c.addView(smallBtn("⚙ সেটিংস খুলুন") { showSettings() })
        c.addView(smallBtn("কথোপকথন মুছুন") { clearHistory() })
        c.addView(smallBtn("ফোনের অ্যাপ অনুমতি (মাইক ইত্যাদি)") {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        })
        val st = card(col, "STATUS")
        st.addView(
            tv(
                "এই সংস্করণে নেই (পরের ধাপে আসবে):\n• \"মাইরা\" বলে ডাকলে ব্যাকগ্রাউন্ডে সাড়া\n• ফোনের সব অ্যাপ নিয়ন্ত্রণ\n• মেসেজ লেখা/পাঠানো",
                13f, 0xFFB0B8D8.toInt()
            )
        )
        return sv
    }

    // ------------------------------------------------------------ ইনপুট ও উত্তর

    private fun send() {
        val t = input.text.toString()
        input.setText("")
        handleUser(t)
    }

    private fun handleUser(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        addMsg("user", text)
        bumpCount()

        val local = tryLocal(text)
        if (local != null) {
            reply(local)
            return
        }

        val key = prefs.getString("apiKey", "") ?: ""
        if (key.isBlank()) {
            reply(
                tr(
                    text,
                    "AI উত্তরের জন্য প্রোফাইল থেকে সেটিংসে আপনার নিজের Gemini API Key বসাতে হবে। অ্যাপ খোলা, সময়/তারিখ ও টাইমার এখনই কাজ করবে।",
                    "AI जवाब के लिए प्रोफ़ाइल की सेटिंग्स में अपनी Gemini API Key डालनी होगी। ऐप खोलना, समय/तारीख और टाइमर अभी काम करेंगे।",
                    "For AI answers, please add your own Gemini API key in Profile > Settings. Opening apps, time/date and timers already work."
                )
            )
            return
        }
        if (!isOnline()) {
            reply(
                tr(
                    text,
                    "ইন্টারনেট নেই, তাই AI উত্তর দিতে পারছি না। অ্যাপ খোলা, সময়/তারিখ ও টাইমার ইন্টারনেট ছাড়াই চলবে।",
                    "इंटरनेट नहीं है, इसलिए AI जवाब नहीं दे सकती। ऐप खोलना, समय/तारीख और टाइमर बिना इंटरनेट चलेंगे।",
                    "No internet, so I can't give AI answers. Opening apps, time/date and timers still work offline."
                )
            )
            return
        }

        setStatus("ভাবছি…")
        val model = (prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL).ifBlank { DEFAULT_MODEL }
        val hist = ArrayList<Pair<String, String>>()
        val start = maxOf(0, history.length() - 14)
        for (i in start until history.length()) {
            val o = history.getJSONObject(i)
            val role = o.getString("role")
            val t = o.getString("text")
            if (role == "maira" && t.startsWith(ERR_PREFIX)) {
                // ব্যর্থ উত্তর ও তার আগের প্রশ্নটা ইতিহাস থেকে বাদ
                if (hist.isNotEmpty() && hist.last().first == "user") hist.removeAt(hist.size - 1)
                continue
            }
            if (role == "maira" && t.startsWith("AI উত্তরের জন্য")) continue
            hist.add(Pair(role, t))
        }
        val sys = systemPrompt()
        val fallback = (prefs.getString("model2", DEFAULT_MODEL2) ?: DEFAULT_MODEL2).ifBlank { DEFAULT_MODEL2 }
        thread {
            var answer: String? = null
            var lastErr = ""
            val models = if (fallback == model) listOf(model) else listOf(model, fallback)
            loop@ for (m in models) {
                for (attempt in 1..2) {
                    try {
                        answer = GeminiClient.chat(key, m, sys, hist)
                        break@loop
                    } catch (e: Exception) {
                        lastErr = e.message ?: "অজানা ত্রুটি"
                        val busy = lastErr.contains("503") || lastErr.contains("429") ||
                            lastErr.contains("demand", true) || lastErr.contains("overload", true)
                        if (!busy) break
                        try {
                            Thread.sleep(1500)
                        } catch (x: Exception) {
                        }
                    }
                }
            }
            val out = answer ?: (ERR_PREFIX + "এখন Google-এর সার্ভার ব্যস্ত বা মডেল পাওয়া যাচ্ছে না, একটু পরে আবার বলুন। (" +
                lastErr.take(120) + ")")
            runOnUiThread { reply(out) }
        }
    }

    private fun systemPrompt(): String {
        var s = "You are Maira, a professional, polished and warm personal voice assistant. " +
            "Reply in the language the user wrote in (Bengali, Hindi or English; if they mix, mix the same way). " +
            "Keep replies short (1 to 3 sentences) because they are spoken aloud, except when the user asks for writing, code or a translation, where you give the full answer. " +
            "Do not use markdown symbols or emojis. Never claim to have done something on the phone that you cannot do."
        val pl = prefs.getString("reply", "auto") ?: "auto"
        if (pl == "bn") s += " Always reply in Bengali."
        if (pl == "hi") s += " Always reply in Hindi."
        if (pl == "en") s += " Always reply in English."
        val name = prefs.getString("name", "") ?: ""
        if (name.isNotBlank()) s += " The user's name is $name."
        if (prefs.getBoolean("gf", false)) {
            s += " Companion mode is ON: speak like a sweet, affectionate and caring close companion, warm and playful with gentle endearing words now and then. " +
                "Stay respectful, never possessive or manipulative, never claim to be human, and still help accurately."
        }
        return s
    }

    private fun reply(text: String) {
        addMsg("maira", text)
        setStatus()
        speak(text)
    }

    // ------------------------------------------------------------ কণ্ঠ

    override fun onInit(code: Int) {
        if (code != TextToSpeech.SUCCESS) return
        ttsReady = true
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                runOnUiThread {
                    avatar.speaking = true
                    wave.active = true
                }
            }

            override fun onDone(utteranceId: String?) {
                runOnUiThread {
                    avatar.speaking = false
                    wave.active = false
                }
            }

            override fun onError(utteranceId: String?) {
                runOnUiThread {
                    avatar.speaking = false
                    wave.active = false
                }
            }
        })
    }

    private fun voiceOn() = prefs.getBoolean("voice", true)

    private fun stopSpeaking() {
        speakGen++
        tts?.stop()
        avatar.speaking = false
        avatar.level = 0f
        wave.active = false
    }

    private fun speak(raw: String) {
        if (!voiceOn()) return
        var text = raw.replace(Regex("[*#_`]"), "")
        if (text.length > 400) {
            val cut = text.take(400)
            val idx = maxOf(cut.lastIndexOf('।'), cut.lastIndexOf('.'))
            text = if (idx > 80) cut.take(idx + 1) else cut
        }
        stopSpeaking()
        val key = prefs.getString("apiKey", "") ?: ""
        if (prefs.getBoolean("gvoice", true) && key.isNotBlank() && isOnline()) {
            val gen = speakGen
            val model = (prefs.getString("ttsModel", DEFAULT_TTS_MODEL) ?: DEFAULT_TTS_MODEL)
                .ifBlank { DEFAULT_TTS_MODEL }
            val voice = prefs.getString("vname", "Kore") ?: "Kore"
            val style = if (prefs.getBoolean("gf", false))
                "Say in a soft, sweet, affectionate and gentle tone, like a caring close friend"
            else
                "Say in a calm, warm, natural and professional tone, like a real person"
            val finalText = text
            thread {
                try {
                    val pcm = GeminiClient.tts(key, model, voice, style, finalText)
                    if (gen != speakGen) return@thread
                    playPcm(pcm, gen)
                } catch (e: Exception) {
                    runOnUiThread {
                        setStatus("Gemini ভয়েস চলেনি (${e.message?.take(90)}) — ফোনের ভয়েসে বলছি")
                        speakLocal(finalText)
                    }
                }
            }
        } else {
            speakLocal(text)
        }
    }

    private fun playPcm(pcm: ByteArray, gen: Int) {
        val sr = 24000
        val minBuf = AudioTrack.getMinBufferSize(
            sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sr)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, 9600))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        runOnUiThread {
            avatar.speaking = true
            wave.active = true
        }
        track.play()
        var off = 0
        val chunk = 4800
        while (off < pcm.size && gen == speakGen) {
            val n = minOf(chunk, pcm.size - off)
            track.write(pcm, off, n)
            var sum = 0.0
            var i = off
            while (i + 1 < off + n) {
                val v = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)).toShort().toInt()
                sum += v.toDouble() * v
                i += 2
            }
            val lv = (sqrt(sum / (n / 2).coerceAtLeast(1)) / 12000.0).toFloat().coerceIn(0f, 1f)
            runOnUiThread {
                avatar.level = lv
                wave.level = lv
            }
            off += n
        }
        try {
            track.stop()
            Thread.sleep(250)
        } catch (e: Exception) {
        }
        track.release()
        runOnUiThread {
            if (gen == speakGen) {
                avatar.speaking = false
                avatar.level = 0f
                wave.active = false
            }
        }
    }

    private fun speakLocal(text: String) {
        if (!ttsReady) return
        val locale = when (detectLang(text)) {
            "bn" -> Locale("bn", "BD")
            "hi" -> Locale("hi", "IN")
            else -> Locale.US
        }
        val r = tts?.setLanguage(locale) ?: TextToSpeech.ERROR
        if (r < TextToSpeech.LANG_AVAILABLE) {
            setStatus("এই ভাষার ভয়েস ফোনে ইনস্টল করা নেই (ফোনের Text-to-speech সেটিংসে ভাষা ডাউনলোড করুন)")
            return
        }
        tts?.setSpeechRate(prefs.getFloat("rate", 1.0f))
        tts?.setPitch(1.05f)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "maira")
    }

    // ------------------------------------------------------------ মাইক্রোফোন

    private fun toggleListening() {
        if (listening) stopListening() else startListening()
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setStatus("এই ফোনে ভয়েস শনাক্তকরণ সার্ভিস পাওয়া যায়নি")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        stopSpeaking()
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onError(error: Int) {
                    listening = false
                    orb.listening = false
                    setStatus(
                        when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH,
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "কিছু শুনতে পাইনি, আবার চেষ্টা করুন"
                            SpeechRecognizer.ERROR_NETWORK,
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                            SpeechRecognizer.ERROR_SERVER -> "ভয়েস শনাক্তকরণে ইন্টারনেট/সার্ভার সমস্যা"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "মাইক্রোফোনের অনুমতি নেই"
                            else -> "ভয়েস শনাক্তকরণে ত্রুটি ($error)"
                        }
                    )
                }

                override fun onResults(results: Bundle?) {
                    listening = false
                    orb.listening = false
                    setStatus()
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (!text.isNullOrBlank()) handleUser(text)
                }
            })
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, prefs.getString("lang", "bn-BD"))
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        recognizer?.startListening(i)
        listening = true
        orb.listening = true
        setStatus()
    }

    private fun stopListening() {
        recognizer?.stopListening()
        listening = false
        orb.listening = false
        setStatus()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            REQ_MIC -> if (ok) startListening() else setStatus("মাইক্রোফোনের অনুমতি দেওয়া হয়নি")
            REQ_CAL -> loadSchedule()
            REQ_LOC -> loadWeather()
            REQ_BT -> loadDevices()
        }
    }

    // ------------------------------------------------------------ লোকাল কমান্ড

    private val openVerbs = listOf(
        "খোলো", "খুলো", "খোল", "খুলুন", "খুলে", "চালাও", "open", "launch",
        "खोलो", "खोल", "खोलिए", "चलाओ"
    )

    private val known = listOf(
        listOf("youtube", "ইউটিউব", "ইউ টিউব", "यूट्यूब", "यू ट्यूब") to "com.google.android.youtube",
        listOf("whatsapp", "whats app", "হোয়াটসঅ্যাপ", "হোয়াটস অ্যাপ", "व्हाट्सएप", "व्हाट्सऐप", "वॉट्सऐप") to "com.whatsapp",
        listOf("facebook", "ফেসবুক", "फेसबुक", "फेसबूक") to "com.facebook.katana",
        listOf("google maps", "maps", "ম্যাপ", "ম্যাপস", "गूगल मैप", "मैप") to "com.google.android.apps.maps",
        listOf("instagram", "ইনস্টাগ্রাম", "इंस्टाग्राम") to "com.instagram.android",
        listOf("telegram", "টেলিগ্রাম", "टेलीग्राम") to "org.telegram.messenger",
        listOf("chrome", "ক্রোম", "क्रोम") to "com.android.chrome",
        listOf("gmail", "জিমেইল", "जीमेल") to "com.google.android.gm"
    )

    private fun tryLocal(text: String): String? {
        val lower = normDigits(text.lowercase())

        if (listOf("টাইমার", "timer", "टाइमर").any { lower.contains(it) }) {
            val n = Regex("(\\d+)").find(lower)?.value?.toIntOrNull()
                ?: return tr(
                    text,
                    "কত সময়ের টাইমার? সংখ্যা দিয়ে বলুন, যেমন \"৫ মিনিটের টাইমার\"।",
                    "कितने समय का टाइमर? संख्या के साथ बोलें, जैसे \"5 मिनट का टाइमर\"।",
                    "How long should the timer be? Say a number, e.g. \"5 minute timer\"."
                )
            val secs = when {
                listOf("সেকেন্ড", "second", "सेकंड", "सेकेंड").any { lower.contains(it) } -> n
                listOf("ঘণ্টা", "ঘন্টা", "hour", "घंटा", "घंटे").any { lower.contains(it) } -> n * 3600
                else -> n * 60
            }
            val i = Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, secs)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "Maira")
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            return try {
                startActivity(i)
                tr(text, "টাইমার চালু করলাম।", "टाइमर चालू कर दिया।", "Timer started.")
            } catch (e: Exception) {
                tr(
                    text,
                    "এই ফোনে টাইমার চালানোর মতো ক্লক অ্যাপ পাইনি।",
                    "इस फोन में टाइमर चलाने वाला क्लॉक ऐप नहीं मिला।",
                    "I couldn't find a clock app that can set a timer."
                )
            }
        }

        if (openVerbs.any { lower.contains(it) }) {
            for ((names, pkg) in known) {
                if (names.any { lower.contains(it) }) {
                    return openPackage(text, pkg, names.first())
                }
            }
            val pm = packageManager
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            var bestPkg: String? = null
            var bestName = ""
            for (ri in pm.queryIntentActivities(main, 0)) {
                val label = ri.loadLabel(pm).toString()
                if (label.length >= 3 && lower.contains(label.lowercase()) && label.length > bestName.length) {
                    bestName = label
                    bestPkg = ri.activityInfo.packageName
                }
            }
            if (bestPkg != null) return openPackage(text, bestPkg, bestName)
        }

        if (listOf("সময়", "কয়টা", "কটা বাজে", "time", "समय", "बजे").any { lower.contains(it) }) {
            val s = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
            return tr(text, "এখন সময় $s।", "अभी समय $s है।", "It's $s.")
        }
        if (listOf("তারিখ", "date", "तारीख", "আজ কী বার", "কি বার").any { lower.contains(it) }) {
            val loc = when (detectLang(text)) {
                "bn" -> Locale("bn", "BD")
                "hi" -> Locale("hi", "IN")
                else -> Locale.US
            }
            val s = SimpleDateFormat("EEEE, d MMMM yyyy", loc).format(Date())
            return tr(text, "আজ $s।", "आज $s है।", "Today is $s.")
        }
        return null
    }

    private fun openPackage(text: String, pkg: String, name: String): String {
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        if (launch == null) {
            return tr(
                text,
                "$name এই ফোনে ইনস্টল করা নেই।",
                "$name इस फोन में इंस्टॉल नहीं है।",
                "$name isn't installed on this phone."
            )
        }
        return try {
            startActivity(launch)
            tr(text, "ঠিক আছে, $name খুলছি।", "ठीक है, $name खोल रही हूँ।", "Okay, opening $name.")
        } catch (e: Exception) {
            tr(text, "$name খুলতে পারলাম না।", "$name नहीं खोल पाई।", "I couldn't open $name.")
        }
    }

    // ------------------------------------------------------------ কার্ডের তথ্য

    private fun loadAll() {
        loadWeather()
        loadSchedule()
        loadDevices()
        loadInsights()
        loadTrending()
    }

    private fun hasPerm(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun httpGet(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 12000
        c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "Maira/1.0")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun note(box: LinearLayout, msg: String, btn: String? = null, action: (() -> Unit)? = null) {
        box.removeAllViews()
        box.addView(tv(msg, 13f, 0xFFB0B8D8.toInt()))
        if (btn != null && action != null) box.addView(smallBtn(btn) { action() })
    }

    private fun wxEmoji(code: Int): String = when (code) {
        0 -> "☀️"
        1, 2 -> "⛅"
        3 -> "☁️"
        45, 48 -> "🌫️"
        in 51..57 -> "🌦️"
        in 61..67 -> "🌧️"
        in 71..77 -> "❄️"
        in 80..82 -> "🌧️"
        in 95..99 -> "⛈️"
        else -> "🌡️"
    }

    private fun wxText(code: Int): String = when (code) {
        0 -> "পরিষ্কার আকাশ"
        1, 2 -> "আংশিক মেঘলা"
        3 -> "মেঘলা"
        45, 48 -> "কুয়াশা"
        in 51..57 -> "গুঁড়িগুঁড়ি বৃষ্টি"
        in 61..67 -> "বৃষ্টি"
        in 71..77 -> "তুষারপাত"
        in 80..82 -> "ভারী বৃষ্টি"
        in 95..99 -> "বজ্রসহ ঝড়"
        else -> "—"
    }

    private fun loadWeather() {
        if (!hasPerm(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            note(weatherBox, "আবহাওয়া দেখাতে লোকেশনের অনুমতি লাগবে।", "অনুমতি দিন") {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION),
                    REQ_LOC
                )
            }
            return
        }
        if (!isOnline()) {
            note(weatherBox, "ইন্টারনেট নেই, আবহাওয়া দেখানো যাচ্ছে না।")
            return
        }
        thread {
            try {
                val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                var loc: Location? = null
                for (pr in lm.getProviders(true)) {
                    val l = try {
                        lm.getLastKnownLocation(pr)
                    } catch (e: SecurityException) {
                        null
                    }
                    if (l != null && (loc == null || l.time > loc.time)) loc = l
                }
                if (loc == null) {
                    runOnUiThread { note(weatherBox, "লোকেশন পাওয়া যায়নি। ফোনের লোকেশন চালু করে আবার খুলুন।") }
                    return@thread
                }
                val url = "https://api.open-meteo.com/v1/forecast?latitude=${loc.latitude}&longitude=${loc.longitude}" +
                    "&current=temperature_2m,weather_code&daily=temperature_2m_max,weather_code&timezone=auto&forecast_days=5"
                val j = JSONObject(httpGet(url))
                val cur = j.getJSONObject("current")
                val temp = Math.round(cur.getDouble("temperature_2m")).toInt()
                val code = cur.getInt("weather_code")
                val daily = j.getJSONObject("daily")
                val days = daily.getJSONArray("time")
                val maxs = daily.getJSONArray("temperature_2m_max")
                val codes = daily.getJSONArray("weather_code")
                var city = ""
                try {
                    val a = Geocoder(this, Locale.getDefault()).getFromLocation(loc.latitude, loc.longitude, 1)
                    if (!a.isNullOrEmpty()) city = a[0].locality ?: a[0].subAdminArea ?: ""
                } catch (e: Exception) {
                }
                runOnUiThread {
                    weatherBox.removeAllViews()
                    if (city.isNotBlank()) weatherBox.addView(tv("📍 $city", 13f, 0xFFB9C6FF.toInt()))
                    weatherBox.addView(tv("$temp°  ${wxEmoji(code)}", 38f, Color.WHITE, true))
                    weatherBox.addView(tv(wxText(code), 14f, 0xFFB9C6FF.toInt()))
                    val line = LinearLayout(this)
                    line.orientation = LinearLayout.HORIZONTAL
                    line.setPadding(0, dp(8), 0, 0)
                    val inFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    val outFmt = SimpleDateFormat("EEE", Locale.getDefault())
                    for (i in 0 until days.length()) {
                        val cell = LinearLayout(this)
                        cell.orientation = LinearLayout.VERTICAL
                        cell.gravity = Gravity.CENTER
                        val d = try {
                            outFmt.format(inFmt.parse(days.getString(i))!!)
                        } catch (e: Exception) {
                            ""
                        }
                        cell.addView(tv(d, 11f, 0xFF8FA8FF.toInt()))
                        cell.addView(tv(wxEmoji(codes.getInt(i)), 18f, Color.WHITE))
                        cell.addView(tv("${Math.round(maxs.getDouble(i)).toInt()}°", 12f, Color.WHITE))
                        line.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
                    }
                    weatherBox.addView(line)
                }
            } catch (e: Exception) {
                runOnUiThread { note(weatherBox, "আবহাওয়া আনা যায়নি: ${e.message?.take(80)}") }
            }
        }
    }

    private fun loadSchedule() {
        if (!hasPerm(Manifest.permission.READ_CALENDAR)) {
            note(scheduleBox, "আজকের সূচি দেখাতে ক্যালেন্ডারের অনুমতি লাগবে।", "অনুমতি দিন") {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.READ_CALENDAR),
                    REQ_CAL
                )
            }
            return
        }
        thread {
            val rows = ArrayList<Triple<String, String, String>>()
            try {
                val start = Calendar.getInstance()
                start.set(Calendar.HOUR_OF_DAY, 0)
                start.set(Calendar.MINUTE, 0)
                start.set(Calendar.SECOND, 0)
                start.set(Calendar.MILLISECOND, 0)
                val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
                ContentUris.appendId(b, start.timeInMillis)
                ContentUris.appendId(b, start.timeInMillis + 86400000L)
                val cur = contentResolver.query(
                    b.build(),
                    arrayOf(
                        CalendarContract.Instances.TITLE,
                        CalendarContract.Instances.BEGIN,
                        CalendarContract.Instances.END
                    ),
                    null, null, "${CalendarContract.Instances.BEGIN} ASC"
                )
                val f = SimpleDateFormat("HH:mm", Locale.getDefault())
                cur?.use {
                    while (it.moveToNext() && rows.size < 6) {
                        val title = it.getString(0) ?: "(শিরোনাম নেই)"
                        val b0 = it.getLong(1)
                        val e0 = it.getLong(2)
                        val mins = ((e0 - b0) / 60000L).toInt()
                        val dur = if (mins >= 60) "${mins / 60} ঘণ্টা" else "$mins মি."
                        rows.add(Triple(f.format(Date(b0)), title, dur))
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { note(scheduleBox, "ক্যালেন্ডার পড়া যায়নি: ${e.message?.take(80)}") }
                return@thread
            }
            todayEvents = rows.joinToString("; ") { "${it.first} ${it.second}" }
            runOnUiThread {
                scheduleBox.removeAllViews()
                if (rows.isEmpty()) scheduleBox.addView(tv("আজ কোনো ইভেন্ট নেই।", 14f, 0xFFB0B8D8.toInt()))
                for (r in rows) scheduleBox.addView(row(r.first, r.second, r.third))
            }
        }
    }

    private fun loadDevices() {
        devicesBox.removeAllViews()
        val bi = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val lvl = bi?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val sc = bi?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (lvl >= 0 && sc > 0) "${lvl * 100 / sc}%" else ""
        devicesBox.addView(row("📱", Build.MODEL, pct))

        val needBt = Build.VERSION.SDK_INT >= 31 && !hasPerm(Manifest.permission.BLUETOOTH_CONNECT)
        if (needBt) {
            devicesBox.addView(tv("ব্লুটুথ ডিভাইস দেখাতে অনুমতি লাগবে।", 13f, 0xFFB0B8D8.toInt()))
            devicesBox.addView(smallBtn("অনুমতি দিন") {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                    REQ_BT
                )
            })
            return
        }
        try {
            val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            if (adapter == null || !adapter.isEnabled) {
                devicesBox.addView(tv("ব্লুটুথ বন্ধ আছে।", 13f, 0xFFB0B8D8.toInt()))
                return
            }
            adapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    val names = try {
                        proxy.connectedDevices.map { it.name ?: "ব্লুটুথ ডিভাইস" }
                    } catch (e: SecurityException) {
                        emptyList()
                    }
                    runOnUiThread {
                        for (n in names) devicesBox.addView(row("🎧", n, "যুক্ত"))
                        if (names.isEmpty()) {
                            devicesBox.addView(tv("কোনো ব্লুটুথ অডিও ডিভাইস যুক্ত নেই।", 13f, 0xFFB0B8D8.toInt()))
                        }
                    }
                    adapter.closeProfileProxy(profile, proxy)
                }

                override fun onServiceDisconnected(profile: Int) {}
            }, BluetoothProfile.A2DP)
        } catch (e: Exception) {
            devicesBox.addView(tv("ব্লুটুথ তথ্য পাওয়া যায়নি।", 13f, 0xFFB0B8D8.toInt()))
        }
    }

    private fun todayKey(offset: Int = 0): String {
        val c = Calendar.getInstance()
        c.add(Calendar.DAY_OF_YEAR, -offset)
        return "cnt_" + SimpleDateFormat("yyyyMMdd", Locale.US).format(c.time)
    }

    private fun bumpCount() {
        val k = todayKey()
        prefs.edit().putInt(k, prefs.getInt(k, 0) + 1).apply()
        if (::insightBox.isInitialized) loadInsights()
    }

    private fun loadInsights() {
        val vals = (6 downTo 0).map { prefs.getInt(todayKey(it), 0) }
        val today = vals.last()
        val yest = vals[vals.size - 2]
        insightBox.removeAllViews()
        val sp = SparkView(this)
        sp.values = vals
        insightBox.addView(sp, LinearLayout.LayoutParams(-1, dp(60)))
        insightBox.addView(tv("আজ আমার সাথে কথা: $today বার", 15f, Color.WHITE, true))
        val diff = today - yest
        val dt = when {
            diff > 0 -> "↑ গতকালের চেয়ে $diff বেশি"
            diff < 0 -> "↓ গতকালের চেয়ে ${-diff} কম"
            else -> "গতকালের সমান"
        }
        insightBox.addView(tv(dt, 13f, 0xFF8FA8FF.toInt()))
    }

    private fun loadTrending() {
        if (!isOnline()) {
            note(trendBox, "ইন্টারনেট নেই, খবর দেখানো যাচ্ছে না।")
            return
        }
        val l = prefs.getString("lang", "bn-BD") ?: "bn-BD"
        val q = when (l) {
            "hi-IN" -> "hl=hi&gl=IN&ceid=IN:hi"
            "en-US" -> "hl=en-IN&gl=IN&ceid=IN:en"
            else -> "hl=bn&gl=IN&ceid=IN:bn"
        }
        thread {
            try {
                val xml = httpGet("https://news.google.com/rss?$q")
                val parser = Xml.newPullParser()
                parser.setInput(java.io.StringReader(xml))
                val items = ArrayList<Pair<String, String>>()
                var inItem = false
                var tag = ""
                var title = ""
                var link = ""
                var ev = parser.eventType
                while (ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT && items.size < 4) {
                    when (ev) {
                        org.xmlpull.v1.XmlPullParser.START_TAG -> {
                            tag = parser.name
                            if (tag == "item") {
                                inItem = true
                                title = ""
                                link = ""
                            }
                        }
                        org.xmlpull.v1.XmlPullParser.TEXT -> {
                            if (inItem && tag == "title") title += parser.text
                            if (inItem && tag == "link") link += parser.text
                        }
                        org.xmlpull.v1.XmlPullParser.END_TAG -> {
                            if (parser.name == "item") {
                                inItem = false
                                items.add(Pair(title.trim(), link.trim()))
                            }
                            tag = ""
                        }
                    }
                    ev = parser.next()
                }
                runOnUiThread {
                    trendBox.removeAllViews()
                    for ((t, lk) in items) {
                        val cut = t.lastIndexOf(" - ")
                        val head = if (cut > 10) t.substring(0, cut) else t
                        val src = if (cut > 10) t.substring(cut + 3) else ""
                        val b = LinearLayout(this)
                        b.orientation = LinearLayout.VERTICAL
                        b.setPadding(0, dp(6), 0, dp(6))
                        b.addView(tv(head, 14f, Color.WHITE))
                        if (src.isNotBlank()) b.addView(tv(src, 11f, 0xFF8FA8FF.toInt()))
                        b.setOnClickListener {
                            try {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(lk)))
                            } catch (e: Exception) {
                            }
                        }
                        trendBox.addView(b)
                    }
                    if (items.isEmpty()) trendBox.addView(tv("কোনো খবর পাওয়া যায়নি।", 13f, 0xFFB0B8D8.toInt()))
                }
            } catch (e: Exception) {
                runOnUiThread { note(trendBox, "খবর আনা যায়নি: ${e.message?.take(80)}") }
            }
        }
    }

    // ------------------------------------------------------------ চ্যাট ইতিহাস

    private fun loadHistory() {
        val arr = try {
            JSONArray(prefs.getString("history", "[]"))
        } catch (e: Exception) {
            JSONArray()
        }
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            history.put(o)
            bubble(o.getString("role"), o.getString("text"))
        }
    }

    private fun addMsg(role: String, text: String) {
        history.put(JSONObject().put("role", role).put("text", text))
        while (history.length() > 100) history.remove(0)
        prefs.edit().putString("history", history.toString()).apply()
        bubble(role, text)
        if (role == "maira" && ::bubbleTv.isInitialized) bubbleTv.text = text
        if (::recentBox.isInitialized) refreshRecent()
    }

    private fun refreshRecent() {
        recentBox.removeAllViews()
        val start = maxOf(0, history.length() - 3)
        for (i in start until history.length()) {
            val o = history.getJSONObject(i)
            val me = o.getString("role") == "user"
            val t = tv((if (me) "আপনি: " else "Maira: ") + o.getString("text"), 14f, Color.WHITE)
            t.maxLines = 3
            t.ellipsize = android.text.TextUtils.TruncateAt.END
            t.setPadding(0, dp(4), 0, dp(4))
            recentBox.addView(t)
        }
        if (::bubbleTv.isInitialized && bubbleTv.text.isNullOrEmpty() && history.length() > 0) {
            for (i in history.length() - 1 downTo 0) {
                val o = history.getJSONObject(i)
                if (o.getString("role") == "maira") {
                    bubbleTv.text = o.getString("text")
                    break
                }
            }
        }
    }

    private fun bubble(role: String, text: String) {
        val user = role == "user"
        val t = tv(text, 16f, Color.WHITE)
        t.setPadding(dp(12), dp(8), dp(12), dp(8))
        val bg = GradientDrawable()
        bg.cornerRadius = dp(16).toFloat()
        bg.setColor(if (user) 0xFF5B2FD6.toInt() else 0xFF14204A.toInt())
        t.background = bg
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.gravity = if (user) Gravity.END else Gravity.START
        lp.setMargins(dp(4), dp(4), dp(4), dp(4))
        t.layoutParams = lp
        chatBox.addView(t)
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun clearHistory() {
        while (history.length() > 0) history.remove(0)
        prefs.edit().putString("history", "[]").apply()
        chatBox.removeAllViews()
        recentBox.removeAllViews()
        bubbleTv.text = ""
    }

    private fun setStatus(msg: String? = null) {
        val s = msg ?: if (listening) "শুনছি…" else "মাইক চেপে কথা বলুন"
        if (::orbLabel.isInitialized) orbLabel.text = s
        if (::chatStatus.isInitialized) chatStatus.text = if (msg == null) "" else msg
    }

    private fun greetingLine(): String {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val g = when {
            h < 5 -> "শুভ রাত্রি"
            h < 12 -> "শুভ সকাল"
            h < 17 -> "শুভ দুপুর"
            h < 20 -> "শুভ সন্ধ্যা"
            else -> "শুভ রাত্রি"
        }
        val name = prefs.getString("name", "") ?: ""
        return if (name.isBlank()) "$g।" else "$g, $name।"
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(n) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // ------------------------------------------------------------ সেটিংস

    private fun showSettings() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(12), dp(20), dp(4))
        fun label(t: String) = TextView(this).apply {
            text = t
            setTextColor(0xFFC9B8FF.toInt())
            setPadding(0, dp(12), 0, dp(4))
        }

        val nameEdit = EditText(this)
        nameEdit.hint = "আপনার নাম"
        nameEdit.setText(prefs.getString("name", ""))
        box.addView(label("আপনার নাম"))
        box.addView(nameEdit)

        val langs = listOf("বাংলা" to "bn-BD", "हिन्दी" to "hi-IN", "English" to "en-US")
        val spin = Spinner(this)
        spin.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, langs.map { it.first })
        spin.setSelection(langs.indexOfFirst { it.second == prefs.getString("lang", "bn-BD") }.coerceAtLeast(0))
        box.addView(label("আমার কথা শোনার ভাষা (মাইক্রোফোন)"))
        box.addView(spin)

        val replies = listOf("ব্যবহারকারীর ভাষায় (অটো)" to "auto", "সবসময় বাংলা" to "bn", "সবসময় हिन्दी" to "hi", "Always English" to "en")
        val rspin = Spinner(this)
        rspin.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, replies.map { it.first })
        rspin.setSelection(replies.indexOfFirst { it.second == prefs.getString("reply", "auto") }.coerceAtLeast(0))
        box.addView(label("উত্তরের ভাষা"))
        box.addView(rspin)

        val gf = CheckBox(this)
        gf.text = "💗 গার্লফ্রেন্ড মুড (আদুরে ও কাছের বন্ধুর মতো কথা)"
        gf.isChecked = prefs.getBoolean("gf", false)
        box.addView(gf)

        val voice = CheckBox(this)
        voice.text = "ভয়েসে উত্তর শোনাও"
        voice.isChecked = voiceOn()
        box.addView(voice)

        val gv = CheckBox(this)
        gv.text = "মানুষের মতো Gemini কণ্ঠ (ইন্টারনেট ও API Key লাগবে)"
        gv.isChecked = prefs.getBoolean("gvoice", true)
        box.addView(gv)

        val voices = listOf("Kore", "Aoede", "Leda", "Zephyr", "Callirrhoe")
        val vspin = Spinner(this)
        vspin.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, voices)
        vspin.setSelection(voices.indexOf(prefs.getString("vname", "Kore")).coerceAtLeast(0))
        box.addView(label("কণ্ঠের ধরন"))
        box.addView(vspin)

        val rate = SeekBar(this)
        rate.max = 15
        rate.progress = ((prefs.getFloat("rate", 1.0f) - 0.5f) * 10f).toInt().coerceIn(0, 15)
        box.addView(label("ফোনের ভয়েসের গতি (Gemini কণ্ঠ না চললে)"))
        box.addView(rate)

        val anim = CheckBox(this)
        anim.text = "অবতারের অ্যানিমেশন"
        anim.isChecked = prefs.getBoolean("anim", true)
        box.addView(anim)

        val keyEdit = EditText(this)
        keyEdit.hint = "আপনার নিজের Gemini API Key"
        keyEdit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        keyEdit.setText(prefs.getString("apiKey", ""))
        box.addView(label("Gemini API Key (শুধু এই ফোনে সংরক্ষিত)"))
        box.addView(keyEdit)

        val modelEdit = EditText(this)
        modelEdit.inputType = InputType.TYPE_CLASS_TEXT
        modelEdit.setText(prefs.getString("model", DEFAULT_MODEL))
        box.addView(label("উত্তরের মডেল"))
        box.addView(modelEdit)

        val model2Edit = EditText(this)
        model2Edit.inputType = InputType.TYPE_CLASS_TEXT
        model2Edit.setText(prefs.getString("model2", DEFAULT_MODEL2))
        box.addView(label("বিকল্প মডেল (প্রধানটা ব্যস্ত থাকলে)"))
        box.addView(model2Edit)

        val ttsEdit = EditText(this)
        ttsEdit.inputType = InputType.TYPE_CLASS_TEXT
        ttsEdit.setText(prefs.getString("ttsModel", DEFAULT_TTS_MODEL))
        box.addView(label("কণ্ঠের মডেল (না চললে বদলান)"))
        box.addView(ttsEdit)

        box.addView(label("অবস্থা"))
        box.addView(TextView(this).apply {
            setTextColor(0xFFB0B8D8.toInt())
            text = if (isOnline()) "ইন্টারনেট: আছে" else "ইন্টারনেট: নেই — AI উত্তর, Gemini কণ্ঠ, আবহাওয়া ও খবর কাজ করবে না"
        })

        val sv = ScrollView(this)
        sv.addView(box)
        AlertDialog.Builder(this)
            .setTitle("সেটিংস")
            .setView(sv)
            .setPositiveButton("সংরক্ষণ") { _, _ ->
                prefs.edit()
                    .putString("name", nameEdit.text.toString().trim())
                    .putString("lang", langs[spin.selectedItemPosition].second)
                    .putString("reply", replies[rspin.selectedItemPosition].second)
                    .putBoolean("gf", gf.isChecked)
                    .putBoolean("voice", voice.isChecked)
                    .putBoolean("gvoice", gv.isChecked)
                    .putString("vname", voices[vspin.selectedItemPosition])
                    .putFloat("rate", 0.5f + rate.progress / 10f)
                    .putBoolean("anim", anim.isChecked)
                    .putString("apiKey", keyEdit.text.toString().trim())
                    .putString("model", modelEdit.text.toString().trim())
                    .putString("model2", model2Edit.text.toString().trim())
                    .putString("ttsModel", ttsEdit.text.toString().trim())
                    .apply()
                avatar.animationsOn = anim.isChecked
                if (!voice.isChecked) stopSpeaking()
                loadTrending()
            }
            .setNegativeButton("বাতিল", null)
            .show()
    }

    // ------------------------------------------------------------ ভাষা সহায়ক

    private fun detectLang(s: String): String {
        var bn = 0
        var hi = 0
        for (ch in s) {
            when (ch.code) {
                in 0x0980..0x09FF -> bn++
                in 0x0900..0x097F -> hi++
            }
        }
        return if (bn == 0 && hi == 0) "en" else if (bn >= hi) "bn" else "hi"
    }

    private fun tr(userText: String, bn: String, hi: String, en: String): String =
        when (detectLang(userText)) {
            "bn" -> bn
            "hi" -> hi
            else -> en
        }

    private fun normDigits(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            when (ch) {
                in '০'..'৯' -> sb.append('0' + (ch - '০'))
                in '०'..'९' -> sb.append('0' + (ch - '०'))
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
