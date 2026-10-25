package com.maira.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
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
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    companion object {
        const val DEFAULT_MODEL = "gemini-2.5-flash"
        const val REQ_MIC = 1
        const val SYSTEM_PROMPT =
            "তুমি Maira, ব্যবহারকারীর ব্যক্তিগত ভার্চুয়াল সহকারী। মিষ্টি, কোমল ও স্নেহপূর্ণ ভঙ্গিতে " +
                "ছোট ও স্পষ্ট উত্তর দাও (সর্বোচ্চ ২-৩ বাক্য), কারণ উত্তর জোরে পড়ে শোনানো হবে। " +
                "ব্যবহারকারী যে ভাষায় (বাংলা, হিন্দি বা ইংরেজি) লিখেছেন সেই ভাষাতেই উত্তর দাও। " +
                "মার্কডাউন চিহ্ন (*, #, `) ব্যবহার করো না। তুমি ফোনে কিছু করতে পারো না এমন কিছু করেছ বলে দাবি করো না।"
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var avatar: AvatarView
    private lateinit var chatBox: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var micBtn: Button
    private lateinit var voiceBtn: Button

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private val history = JSONArray()

    // ---------------------------------------------------------------- লাইফসাইকেল

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("maira", Context.MODE_PRIVATE)

        avatar = findViewById(R.id.avatar)
        chatBox = findViewById(R.id.chatBox)
        scroll = findViewById(R.id.scroll)
        status = findViewById(R.id.status)
        input = findViewById(R.id.input)
        micBtn = findViewById(R.id.btnMic)
        voiceBtn = findViewById(R.id.btnVoice)

        avatar.animationsOn = prefs.getBoolean("anim", true)
        tts = TextToSpeech(this, this)

        findViewById<Button>(R.id.btnSettings).setOnClickListener { showSettings() }
        findViewById<Button>(R.id.btnSend).setOnClickListener { send() }
        micBtn.setOnClickListener { toggleListening() }
        voiceBtn.setOnClickListener {
            prefs.edit().putBoolean("voice", !voiceOn()).apply()
            if (!voiceOn()) {
                tts?.stop()
                avatar.speaking = false
            }
            updateButtons()
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                send()
                true
            } else false
        }

        loadHistory()
        if (history.length() == 0) {
            addMsg(
                "maira",
                "হ্যালো, আমি Maira। লিখুন বা 🎤 চেপে বলুন। যেমন: \"ইউটিউব খোলো\", \"৫ মিনিটের টাইমার দাও\", \"এখন কয়টা বাজে\"।"
            )
        }
        updateButtons()
        setStatus()
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        recognizer?.destroy()
        super.onDestroy()
    }

    override fun onStop() {
        if (listening) stopListening()
        super.onStop()
    }

    // ---------------------------------------------------------------- TTS

    override fun onInit(code: Int) {
        if (code != TextToSpeech.SUCCESS) {
            setStatus("এই ফোনে টেক্সট-টু-স্পিচ চালু করা যায়নি")
            return
        }
        ttsReady = true
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                runOnUiThread { avatar.speaking = true }
            }

            override fun onDone(utteranceId: String?) {
                runOnUiThread { avatar.speaking = false }
            }

            override fun onError(utteranceId: String?) {
                runOnUiThread { avatar.speaking = false }
            }
        })
    }

    private fun voiceOn() = prefs.getBoolean("voice", true)

    private fun speak(raw: String) {
        if (!voiceOn() || !ttsReady) return
        val text = raw.replace(Regex("[*#_`]"), "")
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
        tts?.setPitch(1.1f)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "maira")
    }

    // ---------------------------------------------------------------- মাইক্রোফোন / STT

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
        tts?.stop()
        avatar.speaking = false
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
                    updateButtons()
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
                    updateButtons()
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
        updateButtons()
        setStatus()
    }

    private fun stopListening() {
        recognizer?.stopListening()
        listening = false
        updateButtons()
        setStatus()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startListening()
            else setStatus("মাইক্রোফোনের অনুমতি দেওয়া হয়নি")
        }
    }

    // ---------------------------------------------------------------- ব্যবহারকারীর ইনপুট

    private fun send() {
        val t = input.text.toString()
        input.setText("")
        handleUser(t)
    }

    private fun handleUser(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        addMsg("user", text)

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
                    "AI উত্তরের জন্য সেটিংসে আপনার নিজের Gemini API Key বসাতে হবে। ফোনের অ্যাপ খোলা, সময়/তারিখ ও টাইমার এখনই কাজ করবে।",
                    "AI जवाब के लिए सेटिंग्स में अपनी Gemini API Key डालनी होगी। ऐप खोलना, समय/तारीख और टाइमर अभी काम करेंगे।",
                    "For AI answers, please add your own Gemini API key in Settings. Opening apps, time/date and timers work already."
                )
            )
            return
        }
        if (!isOnline()) {
            reply(
                tr(
                    text,
                    "ইন্টারনেট নেই, তাই AI উত্তর দিতে পারছি না। ফোনের অ্যাপ খোলা, সময়/তারিখ ও টাইমার ইন্টারনেট ছাড়াই চলবে।",
                    "इंटरनेट नहीं है, इसलिए AI जवाब नहीं दे सकती। ऐप खोलना, समय/तारीख और टाइमर बिना इंटरनेट चलेंगे।",
                    "No internet, so I can't give AI answers. Opening apps, time/date and timers still work offline."
                )
            )
            return
        }

        setStatus("ভাবছি…")
        val model = (prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL).ifBlank { DEFAULT_MODEL }
        val body = buildBody()
        thread {
            val answer = try {
                callGemini(key, model, body)
            } catch (e: Exception) {
                "দুঃখিত, উত্তর আনতে সমস্যা হয়েছে: ${e.message}"
            }
            runOnUiThread { reply(answer) }
        }
    }

    private fun reply(text: String) {
        addMsg("maira", text)
        setStatus()
        speak(text)
    }

    // ---------------------------------------------------------------- লোকাল কমান্ড (ইন্টারনেট ছাড়াই)

    private val openVerbs = listOf(
        "খোলো", "খুলো", "খোল", "খুলুন", "খুলে", "চালাও", "open", "launch",
        "खोलो", "खोल", "खोलिए", "चलाओ"
    )

    private val known = listOf(
        listOf("youtube", "ইউটিউব", "ইউ টিউব", "यूट्यूब", "यू ट्यूब") to "com.google.android.youtube",
        listOf("whatsapp", "whats app", "হোয়াটসঅ্যাপ", "হোয়াটস অ্যাপ", "হোয়াটসঅ্যাপ", "व्हाट्सएप", "व्हाट्सऐप", "वॉट्सऐप") to "com.whatsapp",
        listOf("facebook", "ফেসবুক", "फेसबुक", "फेसबूक") to "com.facebook.katana",
        listOf("google maps", "maps", "ম্যাপ", "ম্যাপস", "गूगल मैप", "मैप") to "com.google.android.apps.maps"
    )

    private fun tryLocal(text: String): String? {
        val lower = normDigits(text.lowercase())

        // টাইমার
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

        // অ্যাপ খোলা
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

        // সময়
        if (listOf("সময়", "কয়টা", "কটা বাজে", "time", "समय", "बजे").any { lower.contains(it) }) {
            val s = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
            return tr(text, "এখন সময় $s।", "अभी समय $s है।", "It's $s.")
        }
        // তারিখ
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

    // ---------------------------------------------------------------- Gemini

    private fun buildBody(): String {
        val contents = JSONArray()
        val start = maxOf(0, history.length() - 12)
        for (i in start until history.length()) {
            val o = history.getJSONObject(i)
            val role = if (o.getString("role") == "user") "user" else "model"
            if (contents.length() == 0 && role != "user") continue
            contents.put(
                JSONObject().put("role", role).put(
                    "parts", JSONArray().put(JSONObject().put("text", o.getString("text")))
                )
            )
        }
        return JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT)))
            )
            .put("contents", contents)
            .toString()
    }

    private fun callGemini(key: String, model: String, body: String): String {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val c = url.openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("x-goog-api-key", key)
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val txt = stream.bufferedReader().use { it.readText() }
        if (code !in 200..299) {
            val m = try {
                JSONObject(txt).getJSONObject("error").getString("message")
            } catch (e: Exception) {
                txt.take(200)
            }
            return "API ত্রুটি ($code): $m"
        }
        return JSONObject(txt)
            .getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
            .getString("text").trim()
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(n) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // ---------------------------------------------------------------- চ্যাট UI ও ইতিহাস

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
    }

    private fun bubble(role: String, text: String) {
        val user = role == "user"
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 16f
        tv.setTextColor(Color.WHITE)
        tv.setPadding(dp(12), dp(8), dp(12), dp(8))
        val bg = GradientDrawable()
        bg.cornerRadius = dp(16).toFloat()
        bg.setColor(if (user) 0xFF5B2FD6.toInt() else 0xFF14204A.toInt())
        tv.background = bg
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.gravity = if (user) Gravity.END else Gravity.START
        lp.setMargins(dp(4), dp(4), dp(4), dp(4))
        tv.layoutParams = lp
        chatBox.addView(tv)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun clearHistory() {
        while (history.length() > 0) history.remove(0)
        prefs.edit().putString("history", "[]").apply()
        chatBox.removeAllViews()
    }

    private fun setStatus(msg: String? = null) {
        status.text = msg ?: if (listening) "শুনছি…" else "Maira প্রস্তুত"
    }

    private fun updateButtons() {
        micBtn.text = if (listening) "⏹" else "🎤"
        voiceBtn.text = if (voiceOn()) "🔊" else "🔇"
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- সেটিংস

    private fun showSettings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        fun label(t: String) = TextView(this).apply {
            text = t
            setTextColor(0xFFC9B8FF.toInt())
            setPadding(0, dp(12), 0, dp(4))
        }

        val langs = listOf("বাংলা" to "bn-BD", "हिन्दी" to "hi-IN", "English" to "en-US")
        val spin = Spinner(this)
        spin.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, langs.map { it.first }
        )
        spin.setSelection(
            langs.indexOfFirst { it.second == prefs.getString("lang", "bn-BD") }.coerceAtLeast(0)
        )
        box.addView(label("আমার কথা শোনার ভাষা (মাইক্রোফোন)"))
        box.addView(spin)

        val rate = SeekBar(this)
        rate.max = 15
        rate.progress = ((prefs.getFloat("rate", 1.0f) - 0.5f) * 10f).toInt().coerceIn(0, 15)
        box.addView(label("ভয়েসের গতি"))
        box.addView(rate)

        val voice = CheckBox(this).apply {
            text = "ভয়েসে উত্তর শোনাও"
            isChecked = voiceOn()
        }
        val anim = CheckBox(this).apply {
            text = "অবতারের অ্যানিমেশন"
            isChecked = prefs.getBoolean("anim", true)
        }
        box.addView(voice)
        box.addView(anim)

        val keyEdit = EditText(this).apply {
            hint = "আপনার নিজের Gemini API Key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("apiKey", ""))
        }
        box.addView(label("AI উত্তরের জন্য API Key (শুধু এই ফোনে সংরক্ষিত)"))
        box.addView(keyEdit)

        val modelEdit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(prefs.getString("model", DEFAULT_MODEL))
        }
        box.addView(label("AI মডেলের নাম (ডিফল্ট ঠিক না চললে বদলান)"))
        box.addView(modelEdit)

        box.addView(label("মাইক্রোফোন অনুমতি"))
        box.addView(Button(this).apply {
            text = "ফোনের অ্যাপ সেটিংস খুলুন"
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
            }
        })

        box.addView(Button(this).apply {
            text = "কথোপকথন মুছুন"
            setOnClickListener {
                clearHistory()
            }
        })

        box.addView(label("অবস্থা"))
        box.addView(TextView(this).apply {
            setTextColor(0xFFB0B8D8.toInt())
            text = (if (isOnline()) "ইন্টারনেট: আছে" else "ইন্টারনেট: নেই — AI উত্তর কাজ করবে না") +
                "\nস্ক্রিন বন্ধ অবস্থায় জাগানোর শব্দ: এই সংস্করণে নেই।" +
                "\nমেসেজ লেখা/পাঠানো: এই সংস্করণে নেই।"
        })

        val sv = ScrollView(this)
        sv.addView(box)
        AlertDialog.Builder(this)
            .setTitle("সেটিংস")
            .setView(sv)
            .setPositiveButton("সংরক্ষণ") { _, _ ->
                prefs.edit()
                    .putString("lang", langs[spin.selectedItemPosition].second)
                    .putFloat("rate", 0.5f + rate.progress / 10f)
                    .putBoolean("voice", voice.isChecked)
                    .putBoolean("anim", anim.isChecked)
                    .putString("apiKey", keyEdit.text.toString().trim())
                    .putString("model", modelEdit.text.toString().trim())
                    .apply()
                avatar.animationsOn = anim.isChecked
                if (!voice.isChecked) {
                    tts?.stop()
                    avatar.speaking = false
                }
                updateButtons()
            }
            .setNegativeButton("বাতিল", null)
            .show()
    }

    // ---------------------------------------------------------------- ভাষা সহায়ক

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
