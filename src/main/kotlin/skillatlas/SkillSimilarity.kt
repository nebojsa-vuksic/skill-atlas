package skillatlas

import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Another skill that is similar to a given one, with the similarity as a whole percentage. */
data class SimilarSkill(val path: String, val name: String, val score: Int)

/**
 * Finds similar skills with TF-IDF cosine similarity over words (spec section 5.6). It is a
 * deterministic heuristic: the same skills always give the same result, and nothing leaves
 * the machine.
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
        // Word ids in order of first appearance. Each skill's weights are summed in a scratch
        // array indexed by word id, then stored as a sparse vector sorted by word id.
        val ids = HashMap<String, Int>()
        var scratch = DoubleArray(1024)
        val vectorIds = arrayOfNulls<IntArray>(count)
        val vectorValues = arrayOfNulls<DoubleArray>(count)
        for ((index, skill) in skills.withIndex()) {
            val touched = ArrayList<Int>()
            fun add(text: String, weight: Double) = forEachWord(text) { word ->
                val id = ids.getOrPut(word) { ids.size }
                if (id >= scratch.size) scratch = scratch.copyOf(maxOf(scratch.size * 2, id + 1))
                if (scratch[id] == 0.0) touched += id
                scratch[id] += weight
            }
            add(skill.name, NAME_WEIGHT)
            add(skill.description, DESCRIPTION_WEIGHT)
            contents[skill.path]?.let { add(SkillParser.body(it).take(MAX_BODY_LENGTH), BODY_WEIGHT) }
            val sorted = touched.toIntArray().apply { sort() }
            vectorIds[index] = sorted
            vectorValues[index] = DoubleArray(sorted.size) { scratch[sorted[it]] }
            for (id in sorted) scratch[id] = 0.0
        }

        val documentFrequency = IntArray(ids.size)
        for (vector in vectorIds) for (id in vector!!) documentFrequency[id]++
        val idf = DoubleArray(ids.size) { ln((count + 1.0) / (documentFrequency[it] + 1.0)) + 1.0 }

        // Unit-length TF-IDF vectors, and an inverted index from each word to the skills that have it.
        val postingSkills = Array(ids.size) { IntArray(documentFrequency[it]) }
        val postingValues = Array(ids.size) { DoubleArray(documentFrequency[it]) }
        val postingSizes = IntArray(ids.size)
        for (skill in 0 until count) {
            val sorted = vectorIds[skill]!!
            val values = vectorValues[skill]!!
            for (i in values.indices) values[i] *= idf[sorted[i]]
            val norm = sqrt(values.sumOf { it * it })
            if (norm > 0) for (i in values.indices) values[i] /= norm
            for ((i, id) in sorted.withIndex()) {
                postingSkills[id][postingSizes[id]] = skill
                postingValues[id][postingSizes[id]] = values[i]
                postingSizes[id]++
            }
        }

        // Each pair is compared once: skill a accumulates its dot products with every later skill b.
        val similarity = Array(count) { DoubleArray(count) }
        for (a in 0 until count) {
            val row = similarity[a]
            val aIds = vectorIds[a]!!
            val aValues = vectorValues[a]!!
            for (i in aIds.indices) {
                val skillsWithWord = postingSkills[aIds[i]]
                val values = postingValues[aIds[i]]
                // Postings are in skill order, so the later skills are the ones after a itself.
                var p = skillsWithWord.binarySearch(a) + 1
                while (p < skillsWithWord.size) {
                    row[skillsWithWord[p]] += aValues[i] * values[p]
                    p++
                }
            }
            for (b in a + 1 until count) similarity[b][a] = row[b]
        }

        return skills.withIndex().associate { (a, skill) ->
            skill.path to (0 until count)
                .filter { it != a && similarity[a][it] >= MIN_SIMILARITY }
                .sortedWith(compareByDescending<Int> { similarity[a][it] }.thenBy { skills[it].path })
                .take(MAX_SIMILAR)
                .map { SimilarSkill(skills[it].path, skills[it].name, (similarity[a][it] * 100).roundToInt()) }
        }
    }

    /** The words of [text] that count for similarity, in order, e.g. `mps-tests` gives `mps` and `test`. */
    fun words(text: String): List<String> = buildList { forEachWord(text) { add(it) } }

    /** Calls [action] with each word of [text] that counts, lowercased and without a plural `s`. */
    private inline fun forEachWord(text: String, action: (String) -> Unit) {
        val buffer = CharArray(text.length)
        var start = 0
        while (start < text.length) {
            while (start < text.length && !text[start].isLetterOrDigit()) start++
            var length = 0
            var digitsOnly = true
            while (start + length < text.length && text[start + length].isLetterOrDigit()) {
                val c = text[start + length]
                buffer[length++] = c.lowercaseChar()
                if (!c.isDigit()) digitsOnly = false
            }
            start += length
            if (length < 3 || digitsOnly) continue
            val word = String(buffer, 0, length)
            if (word in STOPWORDS) continue
            val plural = length > 4 && buffer[length - 1] == 's' && buffer[length - 2] != 's'
            action(if (plural) word.substring(0, length - 1) else word)
        }
    }
}
