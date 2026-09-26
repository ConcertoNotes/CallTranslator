package com.example.calltranslator

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.google.mlkit.nl.translate.TranslateLanguage
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** 识别引擎回调，全部在主线程 */
interface SpeechCallback {
    fun onPartial(text: String)

    /** 识别出一句完整的话 */
    fun onSentence(text: String)

    /** 单句模式下没识别到内容就结束了 */
    fun onNothing()

    /** 出错且无法自动恢复，识别已停止 */
    fun onFatal(message: String)
}

interface SpeechEngine {
    /** continuous = true：一直听，每说完一句回调一次 onSentence；false：只识别一句就停 */
    fun start(lang: Lang, continuous: Boolean)

    /** 我说完了：尽快给出结果 */
    fun finish()

    /** 立即停止，之后不再有回调 */
    fun stop()
}

// ---------------- 内置离线识别（Vosk） ----------------

object VoskModels {
    /** 各语言的离线识别模型（每个约 30–90MB，首次使用时下载） */
    val NAMES = mapOf(
        TranslateLanguage.CHINESE to "vosk-model-small-cn-0.22",
        TranslateLanguage.ENGLISH to "vosk-model-small-en-us-0.15",
        TranslateLanguage.SPANISH to "vosk-model-small-es-0.42",
        TranslateLanguage.PORTUGUESE to "vosk-model-small-pt-0.3",
        TranslateLanguage.RUSSIAN to "vosk-model-small-ru-0.22",
        TranslateLanguage.FRENCH to "vosk-model-small-fr-0.22",
        TranslateLanguage.GERMAN to "vosk-model-small-de-0.15",
        TranslateLanguage.ITALIAN to "vosk-model-small-it-0.22",
        TranslateLanguage.JAPANESE to "vosk-model-small-ja-0.22",
        TranslateLanguage.KOREAN to "vosk-model-small-ko-0.22",
        TranslateLanguage.VIETNAMESE to "vosk-model-small-vn-0.4",
        TranslateLanguage.TURKISH to "vosk-model-small-tr-0.3",
        TranslateLanguage.HINDI to "vosk-model-small-hi-0.22",
    )

    fun supports(lang: Lang) = lang.mlkit in NAMES

    private fun dir(ctx: Context, name: String) = File(ctx.filesDir, "vosk/$name")

    fun isInstalled(ctx: Context, lang: Lang): Boolean {
        val name = NAMES[lang.mlkit] ?: return false
        return File(dir(ctx, name), ".ok").exists()
    }

    /** 下载并解压模型，阻塞调用，必须在后台线程执行。progress 为 0–100 */
    fun install(ctx: Context, lang: Lang, progress: (Int) -> Unit): File {
        val name = NAMES.getValue(lang.mlkit)
        val target = dir(ctx, name)
        if (File(target, ".ok").exists()) return target

        val zip = File(ctx.cacheDir, "$name.zip")
        // 先从自己的服务器下载，失败再回退到官方地址
        val urls = listOfNotNull(
            BuildConfig.MODEL_MIRROR.trim().trimEnd('/').takeIf { it.isNotEmpty() }?.let { "$it/$name.zip" },
            "https://alphacephei.com/vosk/models/$name.zip",
        )
        var lastError: Exception? = null
        for (url in urls) {
            try {
                download(url, zip, progress)
                lastError = null
                break
            } catch (e: Exception) {
                lastError = e
                zip.delete()
            }
        }
        lastError?.let { throw it }
        target.deleteRecursively()
        unzip(ctx, zip)
        zip.delete()
        File(target, ".ok").writeText("ok")
        return target
    }

    private fun download(url: String, zip: File, progress: (Int) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        try {
            if (conn.responseCode != 200) throw RuntimeException("${conn.url.host} 返回 ${conn.responseCode}")
            val total = conn.contentLengthLong
            var done = 0L
            var lastPct = -1
            conn.inputStream.use { input ->
                zip.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                progress(pct)
                            }
                        }
                    }
                }
            }
            if (total > 0 && done != total) throw RuntimeException("下载不完整")
        } finally {
            conn.disconnect()
        }
    }

    /** 压缩包里是 <模型名>/... 的结构，解压到 filesDir/vosk/ 下 */
    private fun unzip(ctx: Context, zip: File) {
        val root = File(ctx.filesDir, "vosk")
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                val f = File(root, e.name)
                if (!f.canonicalPath.startsWith(root.canonicalPath + File.separator)) continue
                if (e.isDirectory) {
                    f.mkdirs()
                } else {
                    f.parentFile?.mkdirs()
                    f.outputStream().use { zin.copyTo(it) }
                }
            }
        }
    }
}

class VoskEngine(
    private val models: Map<String, Model>,
    private val cb: SpeechCallback,
) : SpeechEngine {

    private var service: SpeechService? = null
    private var recognizer: Recognizer? = null
    private var gen = 0

    override fun start(lang: Lang, continuous: Boolean) {
        stop()
        val model = models[lang.mlkit]
        if (model == null) {
            cb.onFatal("${lang.label} 的识别模型还没准备好")
            return
        }
        val g = gen
        // 中文、日文结果是按词加空格的，去掉空格
        val joinWords = lang.mlkit == TranslateLanguage.CHINESE || lang.mlkit == TranslateLanguage.JAPANESE
        fun parse(json: String?, key: String): String {
            val t = try {
                JSONObject(json ?: return "").optString(key).trim()
            } catch (e: Exception) {
                ""
            }
            return if (joinWords) t.replace(" ", "") else t
        }
        try {
            val rec = Recognizer(model, SAMPLE_RATE)
            val svc = SpeechService(rec, SAMPLE_RATE)
            recognizer = rec
            service = svc
            svc.startListening(object : org.vosk.android.RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {
                    if (g != gen) return
                    val t = parse(hypothesis, "partial")
                    if (t.isNotEmpty()) cb.onPartial(t)
                }

                override fun onResult(hypothesis: String?) {
                    if (g != gen) return
                    val t = parse(hypothesis, "text")
                    if (t.isEmpty()) return
                    if (!continuous) stop()
                    cb.onSentence(t)
                }

                override fun onFinalResult(hypothesis: String?) {
                    if (g != gen) return
                    val t = parse(hypothesis, "text")
                    stop()
                    if (t.isNotEmpty()) cb.onSentence(t) else cb.onNothing()
                }

                override fun onError(exception: Exception?) {
                    if (g != gen) return
                    stop()
                    cb.onFatal("录音或识别出错：${exception?.message}")
                }

                override fun onTimeout() {
                    if (g != gen) return
                    stop()
                    cb.onNothing()
                }
            })
        } catch (e: Exception) {
            stop()
            cb.onFatal("无法启动录音：${e.message}")
        }
    }

    override fun finish() {
        // 会触发 onFinalResult
        service?.stop()
    }

    override fun stop() {
        gen++
        service?.let {
            it.cancel()
            it.shutdown()
        }
        service = null
        recognizer?.close()
        recognizer = null
    }

    companion object {
        const val SAMPLE_RATE = 16000f
    }
}

// ---------------- Google 语音识别（可选） ----------------

/**
 * 只使用 Google 自己的识别服务，绝不使用系统默认的识别服务
 * （系统默认可能是某个助理 App 的服务，它们通常不允许别的 App 调用）。
 */
class GoogleEngine(
    private val ctx: Context,
    private val component: ComponentName,
    private val cb: SpeechCallback,
) : SpeechEngine {

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var session = 0
    private var errorStreak = 0

    override fun start(lang: Lang, continuous: Boolean) {
        stop()
        errorStreak = 0
        listen(lang, continuous)
    }

    private fun listen(lang: Lang, continuous: Boolean) {
        destroy()
        val sid = session
        val r = SpeechRecognizer.createSpeechRecognizer(ctx, component)
        r.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(b: Bundle?) {
                if (sid != session) return
                b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }?.let { cb.onPartial(it) }
            }

            override fun onResults(b: Bundle?) {
                if (sid != session) return
                errorStreak = 0
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim().orEmpty()
                if (continuous) {
                    restart(lang, 100)
                    if (t.isNotEmpty()) cb.onSentence(t)
                } else {
                    destroy()
                    if (t.isNotEmpty()) cb.onSentence(t) else cb.onNothing()
                }
            }

            override fun onError(error: Int) {
                if (sid != session) return
                val normal = error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                val fatal = when (error) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        "Google App 没有麦克风权限，或不允许调用。可以关掉「使用 Google 语音识别」改用内置识别"
                    12, 13 -> "Google 语音识别不支持${lang.label}" // LANGUAGE_NOT_SUPPORTED / UNAVAILABLE
                    else -> null
                }
                when {
                    fatal != null -> fail(fatal)
                    !continuous -> {
                        destroy()
                        cb.onNothing()
                    }
                    normal -> restart(lang, 100)
                    ++errorStreak >= 5 -> fail("Google 语音识别连续出错（错误码 $error），请检查网络")
                    else -> restart(lang, 800)
                }
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        recognizer = r
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang.speechTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        })
    }

    private fun restart(lang: Lang, delayMs: Long) {
        destroy()
        val sid = session
        handler.postDelayed({ if (sid == session) listen(lang, true) }, delayMs)
    }

    private fun fail(msg: String) {
        stop()
        cb.onFatal(msg)
    }

    private fun destroy() {
        session++
        recognizer?.destroy()
        recognizer = null
    }

    override fun finish() {
        recognizer?.stopListening()
    }

    override fun stop() {
        handler.removeCallbacksAndMessages(null)
        destroy()
    }

    companion object {
        private val PACKAGES = listOf("com.google.android.googlequicksearchbox", "com.google.android.tts")

        /** 找 Google 的识别服务，找不到返回 null */
        fun find(pm: PackageManager): ComponentName? {
            val services = pm.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            for (pkg in PACKAGES) {
                services.firstOrNull { it.serviceInfo.packageName == pkg }?.let {
                    return ComponentName(it.serviceInfo.packageName, it.serviceInfo.name)
                }
            }
            return null
        }
    }
}
