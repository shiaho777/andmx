package com.andmx.agent.plugins

import kotlin.math.ln

object PluginSearch {
    const val K1 = 1.2
    const val B = 0.75
    const val MAX_COUNT = 50

    data class Document(val id: String, val text: String)

    data class Scored(val id: String, val score: Double)

    data class RankedPlugin(val entry: PluginMarketplace.CatalogPlugin, val score: Double)

    fun tokenize(text: String): List<String> {
        val out = mutableListOf<String>()
        val lower = text.lowercase()
        val word = StringBuilder()
        var i = 0
        while (i < lower.length) {
            val c = lower[i]
            when {
                isCjk(c) -> {
                    if (word.isNotEmpty()) {
                        out += word.toString()
                        word.clear()
                    }
                    var j = i
                    while (j < lower.length && isCjk(lower[j])) j++
                    val run = lower.substring(i, j)
                    if (run.length == 1) {
                        out += run
                    } else {
                        for (k in 0 until run.length - 1) out += run.substring(k, k + 2)
                    }
                    i = j
                }
                c.isLetterOrDigit() -> {
                    word.append(c)
                    i++
                }
                else -> {
                    if (word.isNotEmpty()) {
                        out += word.toString()
                        word.clear()
                    }
                    i++
                }
            }
        }
        if (word.isNotEmpty()) out += word.toString()
        return out
    }

    fun rank(query: String, documents: List<Document>): List<Scored> {
        val queryTerms = tokenize(query).distinct()
        if (queryTerms.isEmpty() || documents.isEmpty()) return emptyList()
        val docTokens = documents.map { tokenize(it.text) }
        val lengths = docTokens.map { it.size.toDouble() }
        val avgdl = lengths.average()
        if (avgdl <= 0.0) return emptyList()
        val n = documents.size
        val dfs = HashMap<String, Int>()
        for (tokens in docTokens) {
            for (term in tokens.toSet()) dfs[term] = (dfs[term] ?: 0) + 1
        }
        val tfs = docTokens.map { tokens -> tokens.groupingBy { it }.eachCount() }
        val out = mutableListOf<Scored>()
        for (i in documents.indices) {
            var score = 0.0
            val dl = lengths[i]
            for (term in queryTerms) {
                val tf = tfs[i][term] ?: 0
                if (tf == 0) continue
                val df = dfs[term] ?: 0
                val idf = ln(1.0 + (n - df + 0.5) / (df + 0.5))
                score += idf * (tf * (K1 + 1)) / (tf + K1 * (1 - B + B * dl / avgdl))
            }
            if (score > 0.0) out += Scored(documents[i].id, score)
        }
        return out.sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.id })
    }

    fun searchCatalog(
        query: String,
        plugins: List<PluginMarketplace.CatalogPlugin>,
        count: Int = 10,
    ): List<RankedPlugin> {
        if (plugins.isEmpty()) return emptyList()
        val docs = plugins.mapIndexed { i, p -> Document(i.toString(), documentText(p)) }
        return rank(query, docs)
            .take(count.coerceIn(1, MAX_COUNT))
            .map { RankedPlugin(plugins[it.id.toInt()], it.score) }
    }

    fun documentText(p: PluginMarketplace.CatalogPlugin): String =
        "${p.name} ${p.name} ${p.description} ${p.category}"

    private fun isCjk(c: Char): Boolean = c in '一'..'鿿' || c in '㐀'..'䶿'
}