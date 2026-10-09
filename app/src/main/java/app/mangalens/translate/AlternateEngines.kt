package app.mangalens.translate

import app.mangalens.ocr.Script
import app.mangalens.settings.SourceLang
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * MyMemory's public translation endpoint. No API key is needed, but the service
 * has daily limits and may be unavailable; TranslationService treats it as a
 * fallback rather than promising unlimited use.
 */
class MyMemoryEngine : TranslationEngine {
    override val label = "MyMemory · gratuit"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    override suspend fun translate(items: List<String>, lang: SourceLang): List<String> =
        withContext(Dispatchers.IO) {
            items.map { source ->
                if (source.isBlank()) return@map source
                val url = HttpUrl.Builder()
                    .scheme("https")
                    .host("api.mymemory.translated.net")
                    .addPathSegment("get")
                    .addQueryParameter("q", source)
                    .addQueryParameter("langpair", sourceCode(source, lang) + "|fr")
                    .build()
                val req = Request.Builder().url(url).get().build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw RuntimeException("MyMemory HTTP ${resp.code}")
                    val body = resp.body?.string().orEmpty()
                    val root = JSONObject(body)
                    if (root.optString("responseStatus") !in listOf("200", "202")) {
                        throw RuntimeException(root.optString("responseDetails", "MyMemory indisponible"))
                    }
                    val translated = root.optJSONObject("responseData")?.optString("translatedText")
                        ?.takeIf { it.isNotBlank() } ?: throw RuntimeException("MyMemory n’a renvoyé aucune traduction")
                    if (looksLikeUntranslatedEnglish(source, translated)) {
                        throw RuntimeException("MyMemory a renvoyé le texte anglais sans le traduire")
                    }
                    translated
                }
            }
        }

    private fun sourceCode(text: String, lang: SourceLang): String =
        if (Script.cjkCount(text) == 0) "en" else when (lang) {
            SourceLang.EN -> "en"
            SourceLang.KO -> "ko"
            SourceLang.JA -> "ja"
            SourceLang.ZH -> "zh"
            SourceLang.AUTO -> when {
                text.any { it.code in 0xAC00..0xD7AF } -> "ko"
                text.any { it.code in 0x3040..0x30FF || it.code in 0x4E00..0x9FFF } -> "ja"
                else -> "zh"
            }
        }
}

/**
 * LibreTranslate community instances. No account, API key or billing setup is
 * used. Public instances can rate-limit or disappear, so try a short list and
 * let TranslationService continue to the next provider on failure.
 */
class LibreTranslateEngine : TranslationEngine {
    override val label = "LibreTranslate · gratuit"

    private val endpoints = listOf(
        "translate.terraprint.co",
        "translate.argosopentech.com",
        "libretranslate.de",
    )
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .callTimeout(9, TimeUnit.SECONDS)
        .build()

    override suspend fun translate(items: List<String>, lang: SourceLang): List<String> =
        withContext(Dispatchers.IO) {
            if (items.isEmpty()) return@withContext emptyList()
            var lastError: Exception? = null
            for (host in endpoints) {
                try {
                    val payload = JSONObject()
                        .put("q", org.json.JSONArray(items))
                        .put("source", "auto")
                        .put("target", "fr")
                        .put("format", "text")
                    val request = Request.Builder()
                        .url("https://$host/translate")
                        .header("Content-Type", "application/json")
                        .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build()
                    client.newCall(request).execute().use { resp ->
                        val body = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) throw RuntimeException("LibreTranslate HTTP ${resp.code}: ${body.take(120)}")
                        if (body.trimStart().startsWith("<") || body.contains("<!DOCTYPE", ignoreCase = true)) {
                            throw RuntimeException("Le serveur LibreTranslate a renvoyé une page HTML au lieu du JSON attendu")
                        }
                        val out = if (body.trimStart().startsWith("[")) {
                            val arr = org.json.JSONArray(body)
                            (0 until arr.length()).map { arr.optString(it) }
                        } else {
                            val root = runCatching { JSONObject(body) }.getOrElse {
                                throw RuntimeException("Réponse LibreTranslate non JSON : ${body.take(100)}", it)
                            }
                            val translatedArray = root.optJSONArray("translatedText")
                            when {
                                translatedArray != null -> (0 until translatedArray.length()).map { translatedArray.optString(it) }
                                root.optString("translatedText").isNotBlank() -> listOf(root.optString("translatedText"))
                                else -> throw RuntimeException(root.optString("error", "Réponse LibreTranslate sans translatedText"))
                            }
                        }
                        if (out.size != items.size || out.any { it.isBlank() }) {
                            throw RuntimeException("Réponse LibreTranslate incomplète")
                        }
                        if (allMeaningfulResultsUntranslated(items, out)) {
                            throw RuntimeException("LibreTranslate a renvoyé les textes sources sans les traduire")
                        }
                        return@withContext out
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    lastError = e
                }
            }
            throw RuntimeException(lastError?.message ?: "Aucune instance LibreTranslate disponible", lastError)
        }
}

/**
 * Lingva is a community-hosted, keyless web frontend for Google Translate.
 * It is an independent fallback endpoint, not an official Google API; public
 * instances may be rate-limited or unavailable.
 */
class LingvaEngine : TranslationEngine {
    override val label = "Lingva · gratuit"

    private val hosts = listOf("lingva.ml", "lingva.garudalinux.org")
    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .build()

    override suspend fun translate(items: List<String>, lang: SourceLang): List<String> =
        withContext(Dispatchers.IO) {
            val out = ArrayList<String>(items.size)
            for (source in items) {
                if (source.isBlank()) {
                    out.add("")
                    continue
                }
                var translated: String? = null
                var lastError: Exception? = null
                val sourceCode = when (lang) {
                    SourceLang.EN -> "en"
                    SourceLang.KO -> "ko"
                    SourceLang.JA -> "ja"
                    SourceLang.ZH -> "zh"
                    SourceLang.AUTO -> when {
                        source.any { it.code in 0xAC00..0xD7AF } -> "ko"
                        source.any { it.code in 0x3040..0x30FF } -> "ja"
                        source.any { it.code in 0x4E00..0x9FFF } -> "zh"
                        else -> "en"
                    }
                }
                for (host in hosts) {
                    try {
                        val url = okhttp3.HttpUrl.Builder()
                            .scheme("https")
                            .host(host)
                            .addPathSegment("api")
                            .addPathSegment("v1")
                            .addPathSegment(sourceCode)
                            .addPathSegment("fr")
                            .addPathSegment(source)
                            .build()
                        val request = Request.Builder().url(url).get().build()
                        client.newCall(request).execute().use { resp ->
                            val body = resp.body?.string().orEmpty()
                            if (!resp.isSuccessful) throw RuntimeException("Lingva HTTP ${resp.code}")
                            if (body.trimStart().startsWith("<") || body.contains("<!DOCTYPE", ignoreCase = true)) {
                                throw RuntimeException("Lingva a renvoyé une page HTML au lieu du JSON attendu")
                            }
                            val value = JSONObject(body).optString("translation")
                            if (value.isBlank()) throw RuntimeException("Réponse Lingva vide")
                            if (looksLikeUntranslatedEnglish(source, value)) {
                                throw RuntimeException("Lingva a renvoyé le texte anglais sans le traduire")
                            }
                            translated = value
                        }
                        if (translated != null) break
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        lastError = e
                    }
                }
                out.add(translated ?: throw RuntimeException(lastError?.message ?: "Lingva indisponible", lastError))
            }
            out
        }
}

/**
 * DeepL API engine. A Free API key (usually ending in :fx) uses api-free;
 * other keys use the paid API host. The provider's own usage limits and billing
 * apply to the supplied key.
 */
class DeepLEngine(private val apiKey: String) : TranslationEngine {
    override val label = "DeepL · API"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    override suspend fun translate(items: List<String>, lang: SourceLang): List<String> =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) throw RuntimeException("Ajoute ta clé API DeepL dans les réglages.")
            if (items.isEmpty()) return@withContext emptyList()
            val host = if (apiKey.trim().endsWith(":fx")) "api-free.deepl.com" else "api.deepl.com"
            val form = FormBody.Builder().add("target_lang", "FR")
            items.forEach { form.add("text", it) }
            val request = Request.Builder()
                .url("https://$host/v2/translate")
                .header("Authorization", "DeepL-Auth-Key ${apiKey.trim()}")
                .post(form.build())
                .build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val detail = runCatching { JSONObject(body).optString("message") }.getOrNull().orEmpty()
                    throw RuntimeException("DeepL HTTP ${resp.code}" + if (detail.isBlank()) "" else ": $detail")
                }
                val arr = JSONObject(body).optJSONArray("translations")
                    ?: throw RuntimeException("Réponse DeepL invalide")
                val out = (0 until arr.length()).map { arr.optJSONObject(it)?.optString("text").orEmpty() }
                if (out.size != items.size) throw RuntimeException("Réponse DeepL incomplète")
                out
            }
        }
}

/**
 * Official Microsoft Azure Translator API. Its F0 tier has a monthly free
 * character quota; the user supplies the key and Azure resource region.
 */
class MicrosoftTranslatorEngine(
    private val apiKey: String,
    private val region: String,
) : TranslationEngine {
    override val label = "Microsoft Translator · API"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    override suspend fun translate(items: List<String>, lang: SourceLang): List<String> =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) throw RuntimeException("Ajoute ta clé API Microsoft Translator.")
            if (items.isEmpty()) return@withContext emptyList()
            val url = "https://api.cognitive.microsofttranslator.com/translate?api-version=3.0&to=fr"
            val payload = org.json.JSONArray()
            items.forEach { payload.put(JSONObject().put("Text", it)) }
            val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val builder = Request.Builder()
                .url(url)
                .header("Ocp-Apim-Subscription-Key", apiKey.trim())
                .header("Content-Type", "application/json; charset=utf-8")
                .post(body)
            if (region.isNotBlank()) builder.header("Ocp-Apim-Subscription-Region", region.trim())
            client.newCall(builder.build()).execute().use { resp ->
                val response = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw RuntimeException("Microsoft Translator HTTP ${resp.code}" +
                        if (response.isBlank()) "" else ": ${response.take(180)}")
                }
                val arr = org.json.JSONArray(response)
                val out = (0 until arr.length()).map { i ->
                    arr.optJSONObject(i)?.optJSONArray("translations")
                        ?.optJSONObject(0)?.optString("text").orEmpty()
                }
                if (out.size != items.size || out.any { it.isBlank() }) {
                    throw RuntimeException("Réponse Microsoft Translator incomplète")
                }
                out
            }
        }
}


/** Detect a failed online translation without rejecting short names or expressions. */
private fun looksLikeUntranslatedEnglish(source: String, translated: String): Boolean {
    val original = source.trim().replace(Regex("\\s+"), " ")
    val result = translated.trim().replace(Regex("\\s+"), " ")
    return original.length >= 8 && original.equals(result, ignoreCase = true) &&
        original.any { it in 'A'..'Z' || it in 'a'..'z' } && Script.cjkCount(original) == 0
}

private fun allMeaningfulResultsUntranslated(items: List<String>, results: List<String>): Boolean {
    val candidates = items.zip(results).filter { (source, _) -> source.trim().length >= 8 &&
        source.any { it in 'A'..'Z' || it in 'a'..'z' } && Script.cjkCount(source) == 0 }
    return candidates.isNotEmpty() && candidates.all { (source, result) ->
        source.trim().replace(Regex("\\s+"), " ").equals(
            result.trim().replace(Regex("\\s+"), " "), ignoreCase = true
        )
    }
}
