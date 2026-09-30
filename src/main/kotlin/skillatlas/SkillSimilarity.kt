package skillatlas

import java.util.stream.IntStream
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Another skill that is similar to a given one, with the similarity as a whole percentage. */
data class SimilarSkill(val path: String, val name: String, val score: Int)

/**
 * Finds similar skills with TF-IDF cosine similarity over words (spec section 5.6). It is a
 * deterministic heuristic: the same skills always give the same result, and nothing leaves
 * the machine.
 *
 * The heavy steps run in parallel, one skill per task. Word ids are still given in skill order
 * and every sum is added up in a fixed order, so the result never depends on thread timing.
 */
object SkillSimilarity {
    const val MAX_SIMILAR = 5
    const val MIN_SIMILARITY = 0.05
    const val MAX_BODY_LENGTH = 20_000

    private const val NAME_WEIGHT = 3.0
    private const val DESCRIPTION_WEIGHT = 2.0
    private const val BODY_WEIGHT = 1.0

    /** Common English words that say nothing about what a skill is for. */
    val STOPWORDS = setOf(
        "about", "above", "after", "again", "against", "all", "also", "always", "among", "and", "any", "are",
        "because", "been", "before", "being", "below", "between", "both", "but", "can", "could", "did", "does",
        "doing", "done", "down", "during", "each", "either", "etc", "even", "ever", "every", "few", "for", "from",
        "further", "get", "gets", "had", "has", "have", "having", "her", "here", "hers", "him", "his", "how",
        "however", "into", "its", "itself", "just", "may", "might", "more", "most", "much", "must", "near",
        "neither", "never", "nor", "not", "now", "off", "once", "one", "only", "other", "others", "our", "ours",
        "out", "over", "own", "per", "same", "she", "should", "since", "some", "such", "than", "that", "the",
        "their", "theirs", "them", "then", "there", "these", "they", "this", "those", "through", "thus", "too",
        "under", "until", "upon", "use", "used", "uses", "using", "very", "via", "was", "way", "were", "what",
        "whatever", "when", "whenever", "where", "whether", "which", "while", "who", "whom", "whose", "why",
        "will", "with", "within", "without", "would", "yet", "you", "your", "yours", "yourself",
    )

    /**
     * The similar skills of each of [skills], keyed by [Skill.path]. [contents] holds each
     * skill's file text, keyed by path, as in [Catalog.contents]; its body adds words too.
     */
    fun compute(skills: List<Skill>, contents: Map<String, String>): Map<String, List<SimilarSkill>> {
        val count = skills.size
        val skillWords = arrayOfNulls<SkillWords>(count)
        parallel(count) { skillWords[it] = SkillWords.of(skills[it], contents[skills[it].path]) }

        // Sparse vectors over global word ids, numbered in order of first appearance.
        val ids = HashMap<String, Int>()
        val vectorIds = Array(count) { skill ->
            val words = skillWords[skill]!!.words
            IntArray(words.size) { ids.getOrPut(words[it]) { ids.size } }
        }
        val vectorValues = Array(count) { skillWords[it]!!.weights }

        val documentFrequency = IntArray(ids.size)
        for (vector in vectorIds) for (id in vector) documentFrequency[id]++
        val idf = DoubleArray(ids.size) { ln((count + 1.0) / (documentFrequency[it] + 1.0)) + 1.0 }

        // Unit-length TF-IDF vectors, and an inverted index from each word to the skills that have it, in skill order.
        val postingSkills = Array(ids.size) { IntArray(documentFrequency[it]) }
        val postingValues = Array(ids.size) { DoubleArray(documentFrequency[it]) }
        val postingSizes = IntArray(ids.size)
        for (skill in 0 until count) {
            val vector = vectorIds[skill]
            val values = vectorValues[skill]
            for (i in values.indices) values[i] *= idf[vector[i]]
            val norm = sqrt(values.sumOf { it * it })
            if (norm > 0) for (i in values.indices) values[i] /= norm
            for ((i, id) in vector.withIndex()) {
                postingSkills[id][postingSizes[id]] = skill
                postingValues[id][postingSizes[id]] = values[i]
                postingSizes[id]++
            }
        }

        // Each pair is compared once: skill a accumulates its dot products with every later skill b.
        val similarity = Array(count) { DoubleArray(count) }
        parallel(count) { a ->
            val row = similarity[a]
            val vector = vectorIds[a]
            val values = vectorValues[a]
            for (i in vector.indices) {
                val skillsWithWord = postingSkills[vector[i]]
                val weights = postingValues[vector[i]]
                var p = skillsWithWord.binarySearch(a) + 1
                while (p < skillsWithWord.size) {
                    row[skillsWithWord[p]] += values[i] * weights[p]
                    p++
                }
            }
        }
        for (a in 0 until count) for (b in a + 1 until count) similarity[b][a] = similarity[a][b]

        val similar = arrayOfNulls<List<SimilarSkill>>(count)
        parallel(count) { a ->
            val row = similarity[a]
            fun ranksBefore(b: Int, other: Int) = row[b] > row[other] || (row[b] == row[other] && skills[b].path < skills[other].path)
            // The best skills so far, most similar first, kept by insertion.
            val best = IntArray(MAX_SIMILAR)
            var found = 0
            for (b in 0 until count) {
                if (b == a || row[b] < MIN_SIMILARITY) continue
                var position = found
                while (position > 0 && ranksBefore(b, best[position - 1])) position--
                if (position == MAX_SIMILAR) continue
                best.copyInto(best, position + 1, position, minOf(found, MAX_SIMILAR - 1))
                best[position] = b
                if (found < MAX_SIMILAR) found++
            }
            similar[a] = List(found) { SimilarSkill(skills[best[it]].path, skills[best[it]].name, (row[best[it]] * 100).roundToInt()) }
        }
        return skills.indices.associate { skills[it].path to similar[it]!! }
    }

    /** The words of [text] that count for similarity, in order, e.g. `mps-tests` gives `mps` and `test`. */
    fun words(text: String): List<String> = buildList { forEachWord(text, text.length) { buffer, length -> add(String(buffer, 0, length)) } }

    private fun parallel(count: Int, action: (Int) -> Unit) = IntStream.range(0, count).parallel().forEach { action(it) }

    /** One skill's words in order of first appearance, each with its summed weight. */
    private class SkillWords(val words: List<String>, val weights: DoubleArray) {
        companion object {
            fun of(skill: Skill, file: String?): SkillWords {
                val words = WordTable()
                var weights = DoubleArray(64)
                fun add(text: String, end: Int, weight: Double) = forEachWord(text, end) { buffer, length ->
                    val id = words.idOf(buffer, length)
                    if (id == weights.size) weights = weights.copyOf(weights.size * 2)
                    weights[id] += weight
                }
                add(skill.name, skill.name.length, NAME_WEIGHT)
                add(skill.description, skill.description.length, DESCRIPTION_WEIGHT)
                val body = file?.let(SkillParser::body).orEmpty()
                add(body, minOf(body.length, MAX_BODY_LENGTH), BODY_WEIGHT)
                return SkillWords(words.words, weights.copyOf(words.words.size))
            }
        }
    }

    private val STOPWORD_TABLE = WordTable().apply { for (word in STOPWORDS) idOf(word.toCharArray(), word.length) }

    /**
     * Calls [action] with each word of `text[0, end)` that counts, lowercased and without a
     * plural `s`, held in `buffer[0, length)`. A word seen before then costs no allocation.
     */
    private inline fun forEachWord(text: String, end: Int, action: (buffer: CharArray, length: Int) -> Unit) {
        // Plain array reads and an ASCII fast path keep this loop quick even before the JIT compiles it.
        val chars = text.toCharArray(0, end)
        val buffer = CharArray(end)
        var i = 0
        while (i < end) {
            var length = 0
            var digitsOnly = true
            while (i < end) {
                val c = wordChar(chars[i])
                if (c == NOT_A_WORD_CHAR) break
                buffer[length++] = c
                if (digitsOnly && c !in '0'..'9' && (c < '\u0080' || !c.isDigit())) digitsOnly = false
                i++
            }
            i++
            if (length < 3 || digitsOnly || STOPWORD_TABLE.contains(buffer, length)) continue
            val plural = length > 4 && buffer[length - 1] == 's' && buffer[length - 2] != 's'
            action(buffer, if (plural) length - 1 else length)
        }
    }

    private const val NOT_A_WORD_CHAR = '\u0000'

    /** [c] lowercased if it is a letter or digit, else [NOT_A_WORD_CHAR]. */
    private fun wordChar(c: Char): Char = when {
        c in 'a'..'z' || c in '0'..'9' -> c
        c in 'A'..'Z' -> c + ('a' - 'A')
        c < '\u0080' -> NOT_A_WORD_CHAR
        c.isLetterOrDigit() -> c.lowercaseChar()
        else -> NOT_A_WORD_CHAR
    }

    /** Numbers words in order of first appearance, looking them up straight from a char buffer. */
    private class WordTable {
        val words = ArrayList<String>()
        private var slots = IntArray(256) { -1 }

        fun contains(buffer: CharArray, length: Int) = slots[slot(buffer, length)] >= 0

        fun idOf(buffer: CharArray, length: Int): Int {
            val slot = slot(buffer, length)
            if (slots[slot] >= 0) return slots[slot]
            slots[slot] = words.size
            words += String(buffer, 0, length)
            if (words.size * 2 > slots.size) grow()
            return words.size - 1
        }

        /** The slot holding the word's id, or the empty slot where it would go. */
        private fun slot(buffer: CharArray, length: Int): Int {
            var hash = 0
            for (i in 0 until length) hash = 31 * hash + buffer[i].code
            return probe(hash) { id -> words[id].let { it.length == length && it.regionMatches(buffer, length) } }
        }

        private inline fun probe(hash: Int, matches: (Int) -> Boolean): Int {
            val mask = slots.size - 1
            var slot = (hash * -0x61c88647) ushr 8 and mask
            while (slots[slot] >= 0 && !matches(slots[slot])) slot = (slot + 1) and mask
            return slot
        }

        private fun String.regionMatches(buffer: CharArray, length: Int): Boolean {
            for (i in 0 until length) if (this[i] != buffer[i]) return false
            return true
        }

        private fun grow() {
            slots = IntArray(slots.size * 2) { -1 }
            for ((id, word) in words.withIndex()) slots[probe(word.hashCode()) { false }] = id
        }
    }
}
