package com.example.calltranslator

import android.os.Handler
import android.os.Looper
import com.google.android.gms.tasks.Task
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONException
import org.json.JSONObject
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.Executors

/** 翻译接口，回调都在主线程 */
interface TextTranslator {
    fun translate(text: String, from: Lang, to: Lang, onDone: (Result<String>) -> Unit)
    fun close() {}
}

/** 地址没写 http:// 时自动补上，去掉结尾的 / */
fun normalizeUrl(s: String): String {
    val t = s.trim().trimEnd('/')
    if (t.isEmpty()) return ""
    return if (t.startsWith("http://") || t.startsWith("https://")) t else "http://$t"
}

/**
 * 自己服务器上的 MTranServer：POST /translate
 * 服务器第一次遇到某个语言组合时要先下载模型，可能要几分钟，
 * 所以预热和测试时用很长的 readTimeoutMs，平时翻译用短的。
 */
class MTranTranslator(
    baseUrl: String,
    private val token: String,
    private val readTimeoutMs: Int = 15_000,
) : TextTranslator {

    private val base = normalizeUrl(baseUrl)
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    override fun translate(text: String, from: Lang, to: Lang, onDone: (Result<String>) -> Unit) {
        pool.execute {
            val r = runCatching {
                try {
                    request(text, from, to)
                } catch (e: SocketTimeoutException) {
                    if (e.message.orEmpty().contains("connect")) {
                        throw RuntimeException("连不上 $base，请检查地址、端口和服务器防火墙")
                    }
                    throw RuntimeException("服务器响应超时（第一次使用某种语言时，服务器要先下载翻译模型，请等 1–2 分钟再试）")
                } catch (e: ConnectException) {
                    throw RuntimeException("连不上 $base，请检查地址、端口和服务器防火墙")
                } catch (e: UnknownHostException) {
                    throw RuntimeException("找不到服务器 ${e.message}，请检查地址")
                }
            }
            main.post { onDone(r) }
        }
    }

    private fun request(text: String, from: Lang, to: Lang): String {
        val conn = URL("$base/translate").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8_000
            conn.readTimeout = readTimeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (token.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer ${token.trim()}")
            val body = JSONObject()
                .put("from", code(from))
                .put("to", code(to))
                .put("text", text)
                .put("html", false)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            when {
                status == 401 || status == 403 -> throw RuntimeException("令牌不对（$status）")
                status !in 200..299 -> throw RuntimeException("服务器返回 $status ${resp.take(80)}")
            }
            return parse(resp)
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(resp: String): String {
        try {
            val json = JSONObject(resp)
            for (key in listOf("result", "text", "translation", "data")) {
                val v = json.optString(key)
                if (v.isNotBlank()) return v
            }
            throw RuntimeException("看不懂服务器的返回：${resp.take(80)}")
        } catch (e: JSONException) {
            if (resp.isNotBlank()) return resp.trim()
            throw RuntimeException("服务器没有返回内容")
        }
    }

    /** MTranServer 的简体中文是 zh-Hans，其他语言用两位代码 */
    private fun code(lang: Lang) =
        if (lang.mlkit == TranslateLanguage.CHINESE) "zh-Hans" else lang.mlkit

    override fun close() {
        pool.shutdown()
    }
}

/** Google ML Kit 离线翻译（没设置服务器时使用），需要先下载模型 */
class MlKitTranslator(mine: Lang, theirs: Lang) : TextTranslator {

    private val mineCode = mine.mlkit
    private val toMine = Translation.getClient(
        TranslatorOptions.Builder().setSourceLanguage(theirs.mlkit).setTargetLanguage(mine.mlkit).build()
    )
    private val toTheirs = Translation.getClient(
        TranslatorOptions.Builder().setSourceLanguage(mine.mlkit).setTargetLanguage(theirs.mlkit).build()
    )

    fun download(): Task<Void> {
        val cond = com.google.mlkit.common.model.DownloadConditions.Builder().build()
        return com.google.android.gms.tasks.Tasks.whenAll(
            toMine.downloadModelIfNeeded(cond),
            toTheirs.downloadModelIfNeeded(cond),
        )
    }

    override fun translate(text: String, from: Lang, to: Lang, onDone: (Result<String>) -> Unit) {
        val t = if (to.mlkit == mineCode) toMine else toTheirs
        t.translate(text)
            .addOnSuccessListener { onDone(Result.success(it)) }
            .addOnFailureListener { onDone(Result.failure(it)) }
    }

    override fun close() {
        toMine.close()
        toTheirs.close()
    }
}
