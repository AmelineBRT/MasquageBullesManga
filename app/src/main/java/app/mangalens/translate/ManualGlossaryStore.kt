package app.mangalens.translate

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * User-defined terminology overrides shared by every translation engine.
 *
 * Terms are protected before translation using neutral markers, then restored
 * to the user's exact French wording afterwards. This prevents a service from
 * translating an honorific such as "Miss" as the verb "manquer".
 */
class ManualGlossaryStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("mangalens_manual_glossary", Context.MODE_PRIVATE)

    data class ProtectedText(val text: String, val markers: Map<String, String>)

    @Synchronized
    fun snapshot(): Map<String, String> {
        val result = linkedMapOf<String, String>()
        runCatching {
            val raw = prefs.getString(KEY, null) ?: return result
            val json = JSONObject(raw)
            for (key in json.keys()) {
                val value = json.optString(key).trim()
                if (key.isNotBlank() && value.isNotBlank()) result[key] = value
            }
        }
        return result
    }

    @Synchronized
    fun put(source: String, french: String) {
        val from = source.trim()
        val to = french.trim()
        require(from.isNotEmpty()) { "Le terme anglais ne peut pas être vide." }
        require(to.isNotEmpty()) { "La traduction française ne peut pas être vide." }
        require(from.length <= MAX_TERM_LENGTH && to.length <= MAX_TERM_LENGTH) {
            "Chaque terme est limité à $MAX_TERM_LENGTH caractères."
        }
        val terms = snapshot().toMutableMap()
        terms[from] = to
        persist(terms)
    }

    @Synchronized
    fun remove(source: String) {
        val terms = snapshot().toMutableMap()
        terms.remove(source)
        persist(terms)
    }

    /** Changes when the glossary changes, so old cached translations aren't reused. */
    fun cacheVersion(): Int = snapshot().toSortedMap().entries.hashCode()

    fun protect(source: String): ProtectedText {
        var text = source
        val markers = linkedMapOf<String, String>()
        val terms = snapshot().entries.sortedByDescending { it.key.length }
        for ((index, entry) in terms.withIndex()) {
            val from = entry.key.trim()
            if (from.isEmpty()) continue
            val marker = "MGLSKEEP${index}END"
            val escaped = Regex.escape(from).replace("\\\\ ", "\\\\\\\\s+")
            val pattern = Regex(
                "(?<![\\\\p{L}\\\\p{N}])$escaped(?![\\\\p{L}\\\\p{N}])",
                RegexOption.IGNORE_CASE
            )
            if (pattern.containsMatchIn(text)) {
                text = pattern.replace(text) { marker }
                markers[marker] = entry.value
            }
        }
        return ProtectedText(text, markers)
    }

    fun restore(translated: String, protected: ProtectedText): String {
        var text = translated
        for ((marker, french) in protected.markers) {
            text = Regex(Regex.escape(marker), RegexOption.IGNORE_CASE).replace(text) { french }
        }
        return text
    }

    private fun persist(terms: Map<String, String>) {
        val json = JSONObject()
        terms.entries.sortedByDescending { it.key.length }.forEach { (from, to) -> json.put(from, to) }
        prefs.edit().putString(KEY, json.toString()).apply()
    }

    private companion object {
        const val KEY = "terms"
        const val MAX_TERM_LENGTH = 80
    }
}
