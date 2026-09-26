package com.example.calltranslator

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import org.vosk.Model
import java.util.Locale
import java.util.concurrent.Executors

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

/**
 * 噜噜传声：这台安卓手机放在开免提的 iPhone 旁边。
 * - 听对方：一直识别对方的语言 → 边说边翻 → 显示字幕（可选朗读）
 * - 我说话：点一下按钮说母语 → 翻译 → 用外语大声播放给 iPhone 的麦克风
 * 播放时暂停识别，避免把自己播放的声音又识别一遍。
 *
 * 语音识别默认用内置的 Vosk 离线引擎（App 自己录音），
 * 手机装了 Google 语音服务时可以选择改用 Google。
 */
class MainActivity : AppCompatActivity(), SpeechCallback {

    private enum class Mode { IDLE, THEM, ME, TTS }
    private enum class Level { OK, BUSY, ERROR }
    private enum class Kind { VOSK, GOOGLE, NONE }

    private lateinit var tvTheirs: TextView
    private lateinit var tvMine: TextView
    private lateinit var swReadAloud: SwitchCompat
    private lateinit var swGoogle: SwitchCompat
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
    private val worker = Executors.newSingleThreadExecutor()

    private var mineIdx = 0
    private var theirsIdx = 1
    private val mine get() = LANGS[mineIdx]
    private val theirs get() = LANGS[theirsIdx]

    // 语音识别
    private var googleComponent: ComponentName? = null
    private val voskModels = HashMap<String, Model>() // 只在主线程读写
    private val voskEngine by lazy { VoskEngine(voskModels, this) }
    private var googleEngine: GoogleEngine? = null
    private var engine: SpeechEngine? = null

    // 语音合成
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var utteranceId = 0

    // 翻译
    private var toMine: Translator? = null
    private var toTheirs: Translator? = null
    private var liveSeq = 0 // 实时译文的编号，只显示最新一次的结果

    // 准备状态：语言或引擎一变就重新准备，旧的回调按编号忽略
    private var prepGen = 0
    private var translateReady = false
    private var speechReady = false

    private var listenOn = false
    private var mode = Mode.IDLE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        tvTheirs = findViewById(R.id.tvTheirs)
        tvMine = findViewById(R.id.tvMine)
        swReadAloud = findViewById(R.id.swReadAloud)
        swGoogle = findViewById(R.id.swGoogle)
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
        styleSwitch(swReadAloud)
        styleSwitch(swGoogle)

        mineIdx = prefs.getInt("mine", 0).coerceIn(LANGS.indices)
        theirsIdx = prefs.getInt("theirs", 1).coerceIn(LANGS.indices)
        swReadAloud.isChecked = prefs.getBoolean("readAloud", false)
        swReadAloud.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean("readAloud", on).apply()
        }

        googleComponent = GoogleEngine.find(packageManager)
        googleComponent?.let { googleEngine = GoogleEngine(this, it, this) }
        if (googleComponent != null) {
            findViewById<View>(R.id.rowGoogle).visibility = View.VISIBLE
            swGoogle.isChecked = prefs.getBoolean("useGoogle", false)
            swGoogle.setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean("useGoogle", on).apply()
                stopAll()
                prepare()
            }
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

        onLanguagesChanged()
        updateButtons()

        if (!hasMicPermission()) requestMic()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        engine?.stop()
        worker.shutdownNow()
        voskModels.values.forEach { it.close() }
        tts?.shutdown()
        toMine?.close()
        toTheirs?.close()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (!hasMicPermission()) {
            setStatus("「噜噜传声」没有麦克风权限，请在系统设置里允许", Level.ERROR)
        }
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
        stopAll()
        prepare()
    }

    // ---------- 准备：翻译模型 + 识别模型 ----------

    private fun kindFor(lang: Lang): Kind = when {
        swGoogle.isChecked && googleComponent != null -> Kind.GOOGLE
        VoskModels.supports(lang) -> Kind.VOSK
        googleComponent != null -> Kind.GOOGLE
        else -> Kind.NONE
    }

    private fun engineName(): String {
        val kinds = setOf(kindFor(theirs), kindFor(mine))
        return when {
            kinds == setOf(Kind.VOSK) -> "内置离线识别"
            kinds == setOf(Kind.GOOGLE) -> "Google 语音识别"
            else -> "内置离线识别 + Google"
        }
    }

    private fun prepare() {
        val gen = ++prepGen
        translateReady = false
        speechReady = false
        if (mine == theirs) {
            setStatus("两种语言不能相同", Level.ERROR)
            return
        }
        prepareTranslators(gen)
        prepareSpeech(gen)
    }

    private fun prepareTranslators(gen: Int) {
        toMine?.close()
        toTheirs?.close()
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
        setStatus("正在准备翻译模型（首次需联网下载）…", Level.BUSY)
        val cond = DownloadConditions.Builder().build()
        Tasks.whenAll(a.downloadModelIfNeeded(cond), b.downloadModelIfNeeded(cond))
            .addOnSuccessListener {
                if (gen != prepGen) return@addOnSuccessListener
                translateReady = true
                refreshReady()
            }
            .addOnFailureListener { e ->
                if (gen != prepGen) return@addOnFailureListener
                setStatus("翻译模型下载失败：${e.message}。检查网络后点一下语言重试", Level.ERROR)
            }
    }

    private fun prepareSpeech(gen: Int) {
        val langs = listOf(theirs, mine)
        langs.firstOrNull { kindFor(it) == Kind.NONE }?.let {
            setStatus("本机不支持识别${it.label}（需要安装 Google App）", Level.ERROR)
            return
        }
        val need = langs.filter { kindFor(it) == Kind.VOSK && it.mlkit !in voskModels }
        if (need.isEmpty()) {
            speechReady = true
            refreshReady()
            return
        }
        val ctx = applicationContext
        worker.execute {
            try {
                for (lang in need) {
                    val dir = VoskModels.install(ctx, lang) { pct ->
                        handler.post {
                            if (gen == prepGen) setStatus("正在下载${lang.label}识别模型（首次需要）… $pct%", Level.BUSY)
                        }
                    }
                    handler.post { if (gen == prepGen) setStatus("正在加载${lang.label}识别模型…", Level.BUSY) }
                    val model = Model(dir.absolutePath)
                    handler.post {
                        if (voskModels.containsKey(lang.mlkit)) model.close()
                        else voskModels[lang.mlkit] = model
                    }
                }
                handler.post {
                    if (gen != prepGen) return@post
                    speechReady = true
                    refreshReady()
                }
            } catch (e: Exception) {
                handler.post {
                    if (gen == prepGen) {
                        setStatus("识别模型下载失败：${e.message}。检查网络后点一下语言重试", Level.ERROR)
                    }
                }
            }
        }
    }

    private fun refreshReady() {
        if (translateReady && speechReady) {
            setStatus("就绪 · ${engineName()}", Level.OK)
        } else if (translateReady) {
            // 识别模型还在下载，状态由下载进度负责显示
        } else if (speechReady) {
            setStatus("正在准备翻译模型（首次需联网下载）…", Level.BUSY)
        }
    }

    private fun ready(): Boolean {
        if (translateReady && speechReady) return true
        Toast.makeText(this, "还在准备中，请等顶部显示「就绪」", Toast.LENGTH_SHORT).show()
        return false
    }

    // ---------- 流程控制 ----------

    private fun startListen() {
        if (!hasMicPermission()) {
            requestMic()
            return
        }
        if (!ready()) return
        listenOn = true
        if (mode == Mode.IDLE) {
            mode = Mode.THEM
            startRecognition(theirs, continuous = true)
        }
        updateButtons()
    }

    private fun stopListen() {
        listenOn = false
        if (mode == Mode.THEM) {
            mode = Mode.IDLE
            engine?.stop()
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
            Mode.ME -> engine?.finish()
            Mode.TTS -> {} // 播放中，等播完
            else -> {
                if (!ready()) return
                mode = Mode.ME
                startRecognition(mine, continuous = false)
                updateButtons()
            }
        }
    }

    private fun backToListen(msg: String? = null) {
        msg?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
        mode = if (listenOn) Mode.THEM else Mode.IDLE
        if (mode == Mode.THEM) startRecognition(theirs, continuous = true) else engine?.stop()
        clearLive()
        updateButtons()
    }

    private fun afterSpeak() {
        if (mode == Mode.TTS) backToListen()
    }

    private fun stopAll() {
        listenOn = false
        mode = Mode.IDLE
        engine?.stop()
        tts?.stop()
        clearLive()
        updateButtons()
    }

    private fun startRecognition(lang: Lang, continuous: Boolean) {
        val e: SpeechEngine = when (kindFor(lang)) {
            Kind.VOSK -> voskEngine
            Kind.GOOGLE -> googleEngine!!
            Kind.NONE -> {
                stopAll()
                setStatus("本机不支持识别${lang.label}（需要安装 Google App）", Level.ERROR)
                return
            }
        }
        if (engine !== e) engine?.stop()
        engine = e
        e.start(lang, continuous)
        clearLive()
    }

    // ---------- 识别回调（SpeechCallback） ----------

    override fun onPartial(text: String) {
        if (mode != Mode.THEM && mode != Mode.ME) return
        cardLive.visibility = View.VISIBLE
        tvPartial.text = text
        if (mode == Mode.THEM) translateLive(text)
    }

    override fun onSentence(text: String) {
        when (mode) {
            Mode.THEM -> {
                clearLive()
                handleTheirs(text)
            }
            Mode.ME -> handleMine(text)
            else -> {}
        }
    }

    override fun onNothing() {
        when (mode) {
            Mode.ME -> backToListen("没听清，请再点一次")
            Mode.THEM -> startRecognition(theirs, continuous = true)
            else -> {}
        }
    }

    override fun onFatal(message: String) {
        stopAll()
        setStatus(message, Level.ERROR)
    }

    // ---------- 翻译 + 播放 ----------

    /** 对方还没说完就先翻译已识别的半句，随说随翻。离线翻译很快，所以每次都直接翻。 */
    private fun translateLive(text: String) {
        val tr = toMine ?: return
        val seq = ++liveSeq
        tr.translate(text).addOnSuccessListener { result ->
            if (seq == liveSeq && mode == Mode.THEM) tvLiveTrans.text = result
        }
    }

    /** 清掉实时字幕；正在听的时候显示提示语 */
    private fun clearLive() {
        liveSeq++
        tvLiveTrans.text = ""
        when (mode) {
            Mode.THEM -> {
                cardLive.visibility = View.VISIBLE
                tvPartial.text = "正在听对方…"
            }
            Mode.ME -> {
                cardLive.visibility = View.VISIBLE
                tvPartial.text = "请说${mine.label}，说完点「说完了」"
            }
            else -> {
                tvPartial.text = ""
                cardLive.visibility = View.GONE
            }
        }
    }

    private fun handleTheirs(text: String) {
        val out = addBubble(text, fromMe = false)
        val readAloud = swReadAloud.isChecked
        if (readAloud) {
            // 朗读时暂停识别，否则会把手机自己播放的声音当成对方的话
            engine?.stop()
            mode = Mode.TTS
            clearLive()
            updateButtons()
        }
        val tr = toMine ?: return
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
        engine?.stop()
        mode = Mode.TTS
        clearLive()
        updateButtons()
        val out = addBubble(text, fromMe = true)
        val tr = toTheirs ?: return backToListen()
        tr.translate(text)
            .addOnSuccessListener { result ->
                out.text = result
                scrollToEnd()
                // 点一下自己的气泡可以再播放一遍（对方没听清时用）
                (out.parent as View).setOnClickListener {
                    if (mode == Mode.THEM || mode == Mode.IDLE) {
                        engine?.stop()
                        mode = Mode.TTS
                        clearLive()
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

    private fun styleSwitch(sw: SwitchCompat) {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        sw.thumbTintList = ColorStateList(states, intArrayOf(Color.WHITE, Color.WHITE))
        sw.trackTintList =
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
