package com.example.calltranslator

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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

/**
 * 通话翻译：这台安卓手机放在开免提的 iPhone 旁边。
 * - 听对方：持续识别对方的语言 → 翻译 → 显示字幕（可选朗读）
 * - 我说话：点一下按钮说母语 → 翻译 → 用外语大声播放给 iPhone 的麦克风
 * 播放时暂停识别，避免把自己播放的声音又识别一遍。
 */
class MainActivity : AppCompatActivity() {

    private enum class Mode { IDLE, THEM, ME, TTS }

    private lateinit var spMine: Spinner
    private lateinit var spTheirs: Spinner
    private lateinit var cbReadAloud: CheckBox
    private lateinit var tvStatus: TextView
    private lateinit var tvPartial: TextView
    private lateinit var tvLiveTrans: TextView
    private lateinit var llMessages: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var btnListen: Button
    private lateinit var btnTalk: Button

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("cfg", MODE_PRIVATE) }

    private var recognizer: SpeechRecognizer? = null
    private var session = 0 // 每次识别一个编号，旧识别器的迟到回调直接忽略

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

    private val mine get() = LANGS[spMine.selectedItemPosition]
    private val theirs get() = LANGS[spTheirs.selectedItemPosition]

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        spMine = findViewById(R.id.spMine)
        spTheirs = findViewById(R.id.spTheirs)
        cbReadAloud = findViewById(R.id.cbReadAloud)
        tvStatus = findViewById(R.id.tvStatus)
        tvPartial = findViewById(R.id.tvPartial)
        tvLiveTrans = findViewById(R.id.tvLiveTrans)
        llMessages = findViewById(R.id.llMessages)
        scroll = findViewById(R.id.scroll)
        btnListen = findViewById(R.id.btnListen)
        btnTalk = findViewById(R.id.btnTalk)

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, LANGS)
        spMine.adapter = adapter
        spTheirs.adapter = adapter
        spMine.setSelection(prefs.getInt("mine", 0).coerceIn(LANGS.indices))
        spTheirs.setSelection(prefs.getInt("theirs", 1).coerceIn(LANGS.indices))
        val onPick = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                prefs.edit()
                    .putInt("mine", spMine.selectedItemPosition)
                    .putInt("theirs", spTheirs.selectedItemPosition)
                    .apply()
                prepareTranslators()
                if (mode == Mode.THEM) startRecognition(theirs)
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        spMine.onItemSelectedListener = onPick
        spTheirs.onItemSelectedListener = onPick

        btnListen.setOnClickListener { if (listenOn) stopListen() else startListen() }
        btnTalk.setOnClickListener { talk() }
        findViewById<Button>(R.id.btnClear).setOnClickListener { llMessages.removeAllViews() }
        updateButtons()

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

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setStatus("⚠ 本机没有语音识别服务：请安装或更新 Google App（Google 语音服务）")
        }
        if (!hasMicPermission()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        tts?.shutdown()
        toMine?.close()
        toTheirs?.close()
        super.onDestroy()
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
            setStatus("⚠ 两种语言不能相同")
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
        setStatus("正在准备翻译模型（首次需联网下载，每种语言约 30MB）…")
        val cond = DownloadConditions.Builder().build()
        Tasks.whenAll(a.downloadModelIfNeeded(cond), b.downloadModelIfNeeded(cond))
            .addOnSuccessListener {
                if (gen != translatorGen) return@addOnSuccessListener
                modelsReady = true
                setStatus("✅ 就绪：对方说 ${theirs.label} → 字幕 ${mine.label}；你说 ${mine.label} → 播放 ${theirs.label}")
            }
            .addOnFailureListener { e ->
                if (gen != translatorGen) return@addOnFailureListener
                setStatus("⚠ 模型下载失败：${e.message}。检查网络后重新选一次语言重试")
            }
    }

    // ---------- 流程控制 ----------

    private fun startListen() {
        if (!hasMicPermission()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        listenOn = true
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
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        if (mode == Mode.ME) {
            // 再点一次 = 我说完了
            recognizer?.stopListening()
            return
        }
        handler.removeCallbacksAndMessages(null)
        mode = Mode.ME
        clearLive()
        tts?.stop()
        startRecognition(mine)
        updateButtons()
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

    private fun destroyRecognizer() {
        session++
        recognizer?.destroy()
        recognizer = null
    }

    private fun startRecognition(lang: Lang) {
        destroyRecognizer()
        val sid = session
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (sid != session) return
                tvPartial.text = if (mode == Mode.ME) "🎤 请说${mine.label}…（说完再点一下按钮，或停顿自动结束）" else "👂 正在听对方…"
            }

            override fun onPartialResults(b: Bundle?) {
                if (sid != session) return
                val text = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.takeIf { it.isNotBlank() } ?: return
                tvPartial.text = text
                if (mode == Mode.THEM) translateLive(text)
            }

            override fun onResults(b: Bundle?) {
                if (sid != session) return
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
                when (mode) {
                    Mode.THEM -> {
                        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                            setStatus("⚠ 没有麦克风权限")
                            stopListen()
                        } else {
                            // 没人说话 / 超时属于正常情况，稍后重新开始听
                            val busy = error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                                error == SpeechRecognizer.ERROR_NETWORK ||
                                error == SpeechRecognizer.ERROR_SERVER
                            scheduleListen(if (busy) 800 else 150)
                        }
                    }

                    Mode.ME -> backToListen("没听清（错误 $error），请再点一次")
                    else -> {}
                }
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
    }

    private fun handleTheirs(text: String) {
        val out = addBubble(text, fromMe = false)
        val tr = toMine
        val readAloud = cbReadAloud.isChecked
        if (readAloud) {
            // 朗读时暂停识别，否则会把手机自己播放的声音当成对方的话
            mode = Mode.TTS
            destroyRecognizer()
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

    private fun addBubble(original: String, fromMe: Boolean): TextView {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(if (fromMe) 0xFFDCF8C6.toInt() else 0xFFE3F2FD.toInt())
            }
        }
        val orig = TextView(this).apply {
            text = (if (fromMe) "我：" else "对方：") + original
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Color.DKGRAY)
        }
        val trans = TextView(this).apply {
            text = "…"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
            setTextColor(0xFF111111.toInt())
        }
        box.addView(orig)
        box.addView(trans)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = if (fromMe) Gravity.END else Gravity.START
            topMargin = dp(6)
            if (fromMe) leftMargin = dp(40) else rightMargin = dp(40)
        }
        llMessages.addView(box, lp)
        scrollToEnd()
        return trans
    }

    private fun updateButtons() {
        btnListen.text = if (listenOn) "⏸ 停止听对方" else "▶ 开始听对方"
        btnTalk.text = when (mode) {
            Mode.ME -> "⏹ 我说完了"
            Mode.TTS -> "🔊 播放中…"
            else -> "🎤 我说话"
        }
    }

    private fun setStatus(s: String) {
        tvStatus.text = s
    }

    private fun scrollToEnd() = scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
