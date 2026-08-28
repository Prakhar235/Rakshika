package com.rakshika.app.rag

import kotlin.math.ln
import kotlin.math.sqrt

/** Turns text into a fixed-length vector for similarity search. */
interface TextEmbedder {
    val dimension: Int
    fun embed(text: String): FloatArray
}

/**
 * A dependency-free, fully deterministic on-device embedder using the classic
 * feature-hashing ("hashing trick") technique:
 *
 *  1. lowercase, strip punctuation, split on whitespace
 *  2. build features: word unigrams, word bigrams and character 3-grams
 *  3. hash each feature into one of [dimension] buckets with a signed contribution,
 *     weighted by sub-linear term frequency and a light stop-word penalty
 *  4. L2-normalise so cosine similarity reduces to a dot product
 *
 * It is intentionally simple — it stands in for a transformer sentence-embedder. To use a
 * real model, implement [TextEmbedder] with MediaPipe Text Embedder / ONNX Runtime and pass
 * it to [RagRouteEngine]; nothing else in the pipeline needs to change.
 */
class HashingTextEmbedder(override val dimension: Int = 256) : TextEmbedder {

    override fun embed(text: String): FloatArray {
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return FloatArray(dimension)

        val features = HashMap<String, Int>()
        fun add(f: String) { features[f] = (features[f] ?: 0) + 1 }

        tokens.forEachIndexed { i, tok ->
            add("w:$tok")
            if (i > 0) add("b:${tokens[i - 1]}_$tok")
            if (tok.length >= 3) {
                for (c in 0..tok.length - 3) add("c:${tok.substring(c, c + 3)}")
            } else {
                add("c:$tok")
            }
        }

        val vec = FloatArray(dimension)
        for ((feature, count) in features) {
            val h = hash(feature)
            val bucket = (h and Int.MAX_VALUE) % dimension
            val sign = if (h and 1 == 0) 1f else -1f
            val tf = 1f + ln(count.toFloat())
            val weight = if (isStopFeature(feature)) 0.25f else 1f
            vec[bucket] += sign * tf * weight
        }

        var norm = 0f
        for (v in vec) norm += v * v
        norm = sqrt(norm)
        if (norm > 0f) for (i in vec.indices) vec[i] /= norm
        return vec
    }

    private fun tokenize(text: String): List<String> =
        text.lowercase()
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotBlank() }

    /** FNV-1a — small, fast, stable across runs and platforms. */
    private fun hash(s: String): Int {
        var h = -0x7ee3623b // 2166136261
        for (ch in s) {
            h = h xor ch.code
            h *= 0x01000193
        }
        return h
    }

    private fun isStopFeature(feature: String): Boolean {
        if (!feature.startsWith("w:")) return false
        return feature.substring(2) in STOP_WORDS
    }

    private companion object {
        val STOP_WORDS = setOf(
            "the", "a", "an", "and", "or", "of", "to", "in", "on", "at", "is", "are", "was",
            "were", "be", "by", "for", "with", "as", "it", "its", "this", "that", "from",
            "has", "have", "had", "not", "no", "but", "still", "once", "up", "out", "off"
        )
    }
}
