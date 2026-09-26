package com.example.calltranslator

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Locale

data class Lang(val label: String, val speechTag: String, val mlkit: String) {
    val locale: Locale get() = Locale.forLanguageTag(speechTag)
    override fun toString() = label
}

val LANGS = listOf(
    Lang("中文", "zh-CN", TranslateLanguage.CHINESE),
    Lang("English", "en-US", TranslateLanguage.ENGLISH),
    Lang("Español", "es-ES", TranslateLanguage.SPANISH),
    Lang("Português", "pt-BR", TranslateLanguage.PORTUGUESE),
    Lang("Русский", "ru-RU", TranslateLanguage.RUSSIAN),
    Lang("العربية", "ar-SA", TranslateLanguage.ARABIC),
    Lang("Français", "fr-FR", TranslateLanguage.FRENCH),
    Lang("Deutsch", "de-DE", TranslateLanguage.GERMAN),
    Lang("Italiano", "it-IT", TranslateLanguage.ITALIAN),
    Lang("日本語", "ja-JP", TranslateLanguage.JAPANESE),
    Lang("한국어", "ko-KR", TranslateLanguage.KOREAN),
    Lang("Tiếng Việt", "vi-VN", TranslateLanguage.VIETNAMESE),
    Lang("ไทย", "th-TH", TranslateLanguage.THAI),
    Lang("Bahasa Indonesia", "id-ID", TranslateLanguage.INDONESIAN),
    Lang("Bahasa Melayu", "ms-MY", TranslateLanguage.MALAY),
    Lang("Türkçe", "tr-TR", TranslateLanguage.TURKISH),
    Lang("हिन्दी", "hi-IN", TranslateLanguage.HINDI),
)

/** 优先用谷歌的语音识别；国产系统自带的识别服务常常没有麦克风权限、也只认中文 */
private val PREFERRED_RECOGNIZERS = listOf(
    "com.google.android.googlequicksearchbox",
    "com.google.android.tts",
)

/**
 * 通话翻译：这台安卓手机放在开免提的 iPhone 旁边。
 * - 听对方：持续识别对方的语言 → 边说边翻 → 显示字幕（可选朗读）
 * - 我说话：点一下按钮说母语 → 翻译 → 用外语大声播放给 iPhone 的麦克风
 * 播放时暂停识别，避免把自己播放的声音又识别一遍。
 */
class MainActivity : AppCompatActivity() {

    private enum class Mode { IDLE, THEM, ME, TTS }
    private enum class Level { OK, BUSY, ERROR }

    private lateinit var tvTheirs: TextView
    private lateinit var tvMine: TextView
    private lateinit var swReadAloud: SwitchCompat
    private lateinit var dotStatus: View
    private lateinit var tvStatus: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var cardLive: View
    private lateinit var tvPartial: TextView
    private lateinit var tvLiveTrans: TextView
    private lateinit var llMessages: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var btnListen: TextView
    private lateinit var btnTalk: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("cfg", MODE_PRIVATE) }

    private var mineIdx = 0
    private var theirsIdx = 1
    private val mine get() = LANGS[mineIdx]
    private val theirs get() = LANGS[theirsIdx]

    private var recognizer: SpeechRecognizer? = null
    private var recognizerComponent: ComponentName? = null
    private var session = 0 // 每次识别一个编号，旧识别器的迟到回调直接忽略
    private var errorStreak = 0

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var utteranceId = 0

    private var toMine: Translator? = null
    private var toTheirs: Translator? = null
    private var translatorGen = 0
    private var modelsReady = false
    private var liveSeq = 0 // 实时译文的编号，只显示最新一次的结果

    private var listenOn = false
    private var mode = Mode.IDLE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        tvTheirs = findViewById(R.id.tvTheirs)
        tvMine = findViewById(R.id.tvMine)
        swReadAloud = findViewById(R.id.swReadAloud)
        dotStatus = findViewById(R.id.dotStatus)
        tvStatus = findViewById(R.id.tvStatus)
        tvEmpty = findViewById(R.id.tvEmpty)
        cardLive = findViewById(R.id.cardLive)
        tvPartial = findViewById(R.id.tvPartial)
        tvLiveTrans = findViewById(R.id.tvLiveTrans)
        llMessages = findViewById(R.id.llMessages)
        scroll = findViewById(R.id.scroll)
        btnListen = findViewById(R.id.btnListen)
        btnTalk = findViewById(R.id.btnTalk)

        findViewById<View>(R.id.cardSettings).background = rounded(color(R.color.card), 12)
        cardLive.background = rounded(color(R.color.card), 16)
        styleSwitch()

        mineIdx = prefs.getInt("mine", 0).coerceIn(LANGS.indices)
        theirsIdx = prefs.getInt("theirs", 1).coerceIn(LANGS.indices)
        swReadAloud.isChecked = prefs.getBoolean("readAloud", false)
        swReadAloud.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean("readAloud", on).apply()
        }
        findViewById<View>(R.id.boxTheirs).setOnClickListener {
            pickLanguage("对方说的语言", theirsIdx) { theirsIdx = it; onLanguagesChanged() }
        }
        findViewById<View>(R.id.boxMine).setOnClickListener {
            pickLanguage("我说的语言", mineIdx) { mineIdx = it; onLanguagesChanged() }
        }
        findViewById<View>(R.id.btnSwap).setOnClickListener {
            mineIdx = theirsIdx.also { theirsIdx = mineIdx }
            onLanguagesChanged()
        }
        findViewById<View>(R.id.btnClear).setOnClickListener {
            llMessages.removeAllViews()
            tvEmpty.visibility = View.VISIBLE
        }
        btnListen.setOnClickListener { if (listenOn) stopListen() else startListen() }
        btnTalk.setOnClickListener { talk() }

        tts = TextToSpeech(this) { status -> ttsReady = status == TextToSpeech.SUCCESS }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) {
                handler.post { afterSpeak() }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                handler.post { afterSpeak() }
            }
        })

        recognizerComponent = pickRecognizer()
        onLanguagesChanged()
        updateButtons()

        if (!hasMicPermission()) requestMic()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        tts?.shutdown()
        toMine?.close()
        toTheirs?.close()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (!hasMicPermission()) setStatus("本 App 没有麦克风权限，请在系统设置里允许", Level.ERROR)
    }

    // ---------- 语言 ----------

    private fun pickLanguage(title: String, current: Int, onPicked: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(LANGS.map { it.label }.toTypedArray(), current) { d, which ->
                d.dismiss()
                if (which != current) onPicked(which)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun onLanguagesChanged() {
        tvTheirs.text = "${theirs.label} ▾"
        tvMine.text = "${mine.label} ▾"
        prefs.edit().putInt("mine", mineIdx).putInt("theirs", theirsIdx).apply()
        prepareTranslators()
        if (mode == Mode.THEM) startRecognition(theirs)
    }

    // ---------- 翻译模型 ----------

    private fun prepareTranslators() {
        val gen = ++translatorGen
        toMine?.close()
        toTheirs?.close()
        toMine = null
        toTheirs = null
        modelsReady = false
        if (mine == theirs) {
            setStatus("两种语言不能相同", Level.ERROR)
            return
        }
        val a = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(theirs.mlkit).setTargetLanguage(mine.mlkit).build()
        )
        val b = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(mine.mlkit).setTargetLanguage(theirs.mlkit).build()
        )
        toMine = a
        toTheirs = b
        setStatus("正在准备翻译模型（首次需联网下载，每种语言约 30MB）…", Level.BUSY)
        val cond = DownloadConditions.Builder().build()
        Tasks.whenAll(a.downloadModelIfNeeded(cond), b.downloadModelIfNeeded(cond))
            .addOnSuccessListener {
                if (gen != translatorGen) return@addOnSuccessListener
                modelsReady = true
                setStatus("就绪 · 识别服务：${recognizerLabel()}", Level.OK)
            }
            .addOnFailureListener { e ->
                if (gen != translatorGen) return@addOnFailureListener
                setStatus("模型下载失败：${e.message}。检查网络后点一下语言重试", Level.ERROR)
            }
    }

    // ---------- 流程控制 ----------

    private fun startListen() {
        if (!hasMicPermission()) {
            requestMic()
            return
        }
        listenOn = true
        errorStreak = 0
        if (mode == Mode.IDLE) {
            mode = Mode.THEM
            startRecognition(theirs)
        }
        updateButtons()
    }

    private fun stopListen() {
        listenOn = false
        if (mode == Mode.THEM) {
            mode = Mode.IDLE
            destroyRecognizer()
            clearLive()
        }
        updateButtons()
    }

    private fun talk() {
        if (!hasMicPermission()) {
            requestMic()
            return
        }
        when (mode) {
            // 再点一次 = 我说完了
            Mode.ME -> recognizer?.stopListening()
            Mode.TTS -> {} // 播放中，等播完
            else -> {
                handler.removeCallbacksAndMessages(null)
                mode = Mode.ME
                clearLive()
                startRecognition(mine)
                updateButtons()
            }
        }
    }

    private fun backToListen(msg: String? = null) {
        msg?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
        mode = if (listenOn) Mode.THEM else Mode.IDLE
        clearLive()
        if (mode == Mode.THEM) startRecognition(theirs) else destroyRecognizer()
        updateButtons()
    }

    private fun scheduleListen(delayMs: Long = 150) {
        handler.postDelayed({
            if (listenOn && mode == Mode.THEM) startRecognition(theirs)
        }, delayMs)
    }

    private fun afterSpeak() {
        if (mode == Mode.TTS) backToListen()
    }

    // ---------- 语音识别 ----------

    private fun pickRecognizer(): ComponentName? {
        val services = packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
        if (services.isEmpty()) {
            setStatus("本机没有语音识别服务：请安装 Google App", Level.ERROR)
            return null
        }
        for (pkg in PREFERRED_RECOGNIZERS) {
            services.firstOrNull { it.serviceInfo.packageName == pkg }?.let {
                return ComponentName(it.serviceInfo.packageName, it.serviceInfo.name)
            }
        }
        return null // 没装谷歌的，就用系统默认
    }

    private fun recognizerPackage(): String? =
        recognizerComponent?.packageName
            ?: Settings.Secure.getString(contentResolver, "voice_recognition_service")
                ?.let { ComponentName.unflattenFromString(it)?.packageName }

    private fun recognizerLabel(): String {
        val pkg = recognizerPackage() ?: return "系统默认"
        return try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            pkg
        }
    }

    private fun destroyRecognizer() {
        session++
        recognizer?.destroy()
        recognizer = null
    }

    private fun startRecognition(lang: Lang) {
        destroyRecognizer()
        val sid = session
        val r = recognizerComponent?.let { SpeechRecognizer.createSpeechRecognizer(this, it) }
            ?: SpeechRecognizer.createSpeechRecognizer(this)
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (sid != session) return
                cardLive.visibility = View.VISIBLE
                if (tvLiveTrans.text.isEmpty()) {
                    tvPartial.text = if (mode == Mode.ME) "请说${mine.label}，说完点「说完了」" else "正在听对方…"
                }
            }

            override fun onPartialResults(b: Bundle?) {
                if (sid != session) return
                val text = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.takeIf { it.isNotBlank() } ?: return
                cardLive.visibility = View.VISIBLE
                tvPartial.text = text
                if (mode == Mode.THEM) translateLive(text)
            }

            override fun onResults(b: Bundle?) {
                if (sid != session) return
                errorStreak = 0
                val text = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim().orEmpty()
                clearLive()
                when (mode) {
                    Mode.THEM -> if (text.isEmpty()) scheduleListen() else handleTheirs(text)
                    Mode.ME -> if (text.isEmpty()) backToListen("没听清，请再点一次") else handleMine(text)
                    else -> {}
                }
            }

            override fun onError(error: Int) {
                if (sid != session) return
                onRecognitionError(error)
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        recognizer = r
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang.speechTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        r.startListening(intent)
    }

    private fun onRecognitionError(error: Int) {
        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            if (!hasMicPermission()) {
                stopAll()
                requestMic()
            } else {
                // 本 App 有权限，缺权限的是语音识别服务那个 App
                stopAll()
                showRecognizerPermissionDialog()
            }
            return
        }
        if (error == 12 || error == 13) { // ERROR_LANGUAGE_NOT_SUPPORTED / UNAVAILABLE
            val lang = if (mode == Mode.ME) mine.label else theirs.label
            stopAll()
            setStatus("「${recognizerLabel()}」不支持识别$lang", Level.ERROR)
            return
        }
        val normal = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
        when (mode) {
            Mode.THEM -> {
                if (normal) {
                    // 没人说话，马上重新开始听
                    errorStreak = 0
                    scheduleListen()
                } else if (++errorStreak >= 5) {
                    stopAll()
                    setStatus("语音识别连续出错（错误码 $error），请检查网络后重新点「开始听」", Level.ERROR)
                } else {
                    scheduleListen(800)
                }
            }

            Mode.ME -> backToListen(if (normal) "没听清，请再点一次" else "识别出错（错误码 $error），请再点一次")
            else -> {}
        }
    }

    private fun stopAll() {
        handler.removeCallbacksAndMessages(null)
        listenOn = false
        mode = Mode.IDLE
        destroyRecognizer()
        clearLive()
        updateButtons()
    }

    private fun showRecognizerPermissionDialog() {
        val pkg = recognizerPackage()
        val name = recognizerLabel()
        setStatus("「$name」没有麦克风权限", Level.ERROR)
        val dialog = AlertDialog.Builder(this)
            .setTitle("需要给「$name」开麦克风权限")
            .setMessage(
                "本 App 的麦克风权限已经打开，但它调用的语音识别服务「$name」没有麦克风权限。\n\n" +
                    "点「去开启」→ 权限 → 麦克风 → 允许，然后回来重新点「开始听」。"
            )
            .setNegativeButton("取消", null)
        if (pkg != null) {
            dialog.setPositiveButton("去开启") { _, _ ->
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                )
            }
        }
        dialog.show()
    }

    // ---------- 翻译 + 播放 ----------

    /** 对方还没说完就先翻译已识别的半句，随说随翻。离线翻译很快，所以每次都直接翻。 */
    private fun translateLive(text: String) {
        val tr = toMine ?: return
        if (!modelsReady) return
        val seq = ++liveSeq
        tr.translate(text).addOnSuccessListener { result ->
            if (seq == liveSeq && mode == Mode.THEM) tvLiveTrans.text = result
        }
    }

    private fun clearLive() {
        liveSeq++
        tvPartial.text = ""
        tvLiveTrans.text = ""
        cardLive.visibility = View.GONE
    }

    private fun handleTheirs(text: String) {
        val out = addBubble(text, fromMe = false)
        val tr = toMine
        val readAloud = swReadAloud.isChecked
        if (readAloud) {
            // 朗读时暂停识别，否则会把手机自己播放的声音当成对方的话
            mode = Mode.TTS
            destroyRecognizer()
            updateButtons()
        } else {
            scheduleListen()
        }
        if (!modelsReady || tr == null) {
            out.text = "（翻译模型还没准备好）"
            if (readAloud) backToListen()
            return
        }
        tr.translate(text)
            .addOnSuccessListener { result ->
                out.text = result
                scrollToEnd()
                if (readAloud && mode == Mode.TTS) speak(result, mine)
            }
            .addOnFailureListener { e ->
                out.text = "翻译失败：${e.message}"
                if (readAloud && mode == Mode.TTS) backToListen()
            }
    }

    private fun handleMine(text: String) {
        mode = Mode.TTS
        destroyRecognizer()
        updateButtons()
        val out = addBubble(text, fromMe = true)
        val tr = toTheirs
        if (!modelsReady || tr == null) {
            out.text = "（翻译模型还没准备好）"
            backToListen()
            return
        }
        tr.translate(text)
            .addOnSuccessListener { result ->
                out.text = result
                scrollToEnd()
                // 点一下自己的气泡可以再播放一遍（对方没听清时用）
                (out.parent as View).setOnClickListener {
                    if (mode == Mode.THEM || mode == Mode.IDLE) {
                        mode = Mode.TTS
                        destroyRecognizer()
                        speak(result, theirs)
                    }
                }
                if (mode == Mode.TTS) speak(result, theirs)
            }
            .addOnFailureListener { e ->
                out.text = "翻译失败：${e.message}"
                backToListen()
            }
    }

    private fun speak(text: String, lang: Lang) {
        val engine = tts
        if (!ttsReady || engine == null) {
            backToListen("语音引擎未就绪")
            return
        }
        val r = engine.setLanguage(lang.locale)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            backToListen("手机缺少「${lang.label}」语音包：设置 → 文字转语音 里安装")
            return
        }
        mode = Mode.TTS
        updateButtons()
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u${++utteranceId}")
    }

    // ---------- 界面 ----------

    /** iMessage 风格气泡：上面是译文（大），下面是原文（小）。返回译文 TextView */
    private fun addBubble(original: String, fromMe: Boolean): TextView {
        tvEmpty.visibility = View.GONE
        val maxW = (resources.displayMetrics.widthPixels * 0.78).toInt()
        val fg = if (fromMe) Color.WHITE else color(R.color.label)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(9), dp(14), dp(10))
            background = rounded(color(if (fromMe) R.color.bubble_me else R.color.bubble_them), 18)
        }
        val trans = TextView(this).apply {
            text = "…"
            maxWidth = maxW
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(fg)
            setLineSpacing(0f, 1.1f)
        }
        val orig = TextView(this).apply {
            text = original
            maxWidth = maxW
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(if (fromMe) 0xCCFFFFFF.toInt() else color(R.color.secondary))
            setPadding(0, dp(3), 0, 0)
        }
        box.addView(trans)
        box.addView(orig)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = if (fromMe) Gravity.END else Gravity.START
            topMargin = dp(6)
        }
        llMessages.addView(box, lp)
        scrollToEnd()
        return trans
    }

    private fun updateButtons() {
        if (listenOn) {
            styleButton(btnListen, "停止听", color(R.color.fill), color(R.color.red))
        } else {
            styleButton(btnListen, "开始听", color(R.color.fill), color(R.color.accent))
        }
        when (mode) {
            Mode.ME -> styleButton(btnTalk, "说完了", color(R.color.red), Color.WHITE)
            Mode.TTS -> styleButton(btnTalk, "播放中…", color(R.color.fill), color(R.color.secondary))
            else -> styleButton(btnTalk, "我说话", color(R.color.accent), Color.WHITE)
        }
    }

    private fun styleButton(b: TextView, text: String, bg: Int, fg: Int) {
        b.text = text
        b.setTextColor(fg)
        b.background = rounded(bg, 14)
    }

    private fun styleSwitch() {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        swReadAloud.thumbTintList = ColorStateList(states, intArrayOf(Color.WHITE, Color.WHITE))
        swReadAloud.trackTintList =
            ColorStateList(states, intArrayOf(color(R.color.green), color(R.color.fill)))
    }

    private fun setStatus(s: String, level: Level) {
        tvStatus.text = s
        val c = when (level) {
            Level.OK -> R.color.green
            Level.BUSY -> R.color.orange
            Level.ERROR -> R.color.red
        }
        dotStatus.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color(c))
        }
    }

    private fun rounded(c: Int, radiusDp: Int) = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(c)
    }

    private fun scrollToEnd() = scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

    private fun requestMic() =
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun color(id: Int) = ContextCompat.getColor(this, id)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
