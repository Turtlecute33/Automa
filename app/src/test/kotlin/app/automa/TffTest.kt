package app.automa

import app.automa.env.Tff
import org.json.JSONObject
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The TFF model list (DESIGN §8): the gateway answer is untrusted, and the managed opencode config. Made-up ids. */
class TffTest {
    @Test
    fun modelIds() {
        val json = """
            {"object": "list", "data": [
              {"id": "zeta-coder-7b", "object": "model"},
              {"id": "alpha/chat-1.5:free", "object": "model"},
              {"id": "zeta-coder-7b"},
              {"id": "bad\u001b[31mred"},
              {"id": "has space"},
              {"id": "${"x".repeat(129)}"},
              {"id": ""},
              {"object": "model"},
              42
            ]}
        """.trimIndent()
        assertEquals(listOf("alpha/chat-1.5:free", "zeta-coder-7b"), Tff.models(json))
    }

    @Test
    fun notAModelList() {
        assertFailsWith<IOException> { Tff.models("""{"error": {"message": "no key"}}""") }
    }

    @Test
    fun managedConfig() {
        val text = Tff.config(listOf("alpha-1", "beta-2"))
        val tff = JSONObject(text).getJSONObject("provider").getJSONObject(Tff.PROVIDER)
        assertEquals("@ai-sdk/openai-compatible", tff.getString("npm"))
        assertEquals(Tff.KEY, tff.getJSONArray("env").getString(0))
        assertEquals(Tff.BASE_URL, tff.getJSONObject("options").getString("baseURL"))
        assertEquals(setOf("alpha-1", "beta-2"), tff.getJSONObject("models").keys().asSequence().toSet())
        assertEquals(2, Tff.count(text))
    }
}
