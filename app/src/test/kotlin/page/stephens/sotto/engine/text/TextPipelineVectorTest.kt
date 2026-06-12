package page.stephens.sotto.engine.text

// The port-equivalence contract: every case in pipeline-vectors.json (generated
// by the SERVER's tools/generate_pipeline_vectors.py - the reference
// implementation) must reproduce byte-for-byte. If this fails after a server
// pipeline change, regenerate the vectors there and update both repos.

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class TextPipelineVectorTest {

    private val vectors: JSONObject by lazy {
        val text = javaClass.classLoader!!.getResourceAsStream("pipeline-vectors.json")!!
            .readBytes().decodeToString()
        JSONObject(text)
    }

    @Test
    fun `cleanMarkdown reproduces every reference vector byte-for-byte`() {
        val cases = vectors.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            assertEquals("case '${case.getString("name")}' (cleaned)",
                case.getString("cleaned"), TextPipeline.cleanMarkdown(case.getString("input")))
        }
    }

    @Test
    fun `chunk reproduces every reference vector byte-for-byte`() {
        val limit = vectors.getJSONObject("_meta").getInt("kokoro_chunk_limit")
        val cases = vectors.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val expected = case.getJSONArray("chunks").let { a -> List(a.length()) { a.getString(it) } }
            assertEquals("case '${case.getString("name")}' (chunks)",
                expected, TextPipeline.chunk(case.getString("cleaned"), limit))
        }
    }
}
