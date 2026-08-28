package com.rakshika.app.rag

/**
 * A minimal in-memory vector database: every [SafetyDoc] is embedded once on insert and
 * queries run an exact cosine-similarity scan. Fine for the few dozen notes a demo dataset
 * holds; swap in a real ANN index (ObjectBox, sqlite-vec, …) if the corpus ever grows.
 *
 * Vectors are stored already L2-normalised (see [HashingTextEmbedder]), so cosine
 * similarity is just a dot product.
 */
class InMemoryVectorStore(private val embedder: TextEmbedder) {

    data class Match(val doc: SafetyDoc, val score: Float)

    private data class Record(val doc: SafetyDoc, val vector: FloatArray)

    private val records = mutableListOf<Record>()

    val size: Int get() = records.size

    fun clear() = records.clear()

    fun insertAll(docs: List<SafetyDoc>) {
        docs.forEach { records += Record(it, embedder.embed(it.text)) }
    }

    /** Top [topK] documents by cosine similarity to [text], highest first. */
    fun query(text: String, topK: Int): List<Match> {
        if (records.isEmpty()) return emptyList()
        val q = embedder.embed(text)
        return records
            .map { Match(it.doc, dot(q, it.vector)) }
            .sortedByDescending { it.score }
            .take(topK)
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        val n = minOf(a.size, b.size)
        for (i in 0 until n) s += a[i] * b[i]
        return s
    }
}
