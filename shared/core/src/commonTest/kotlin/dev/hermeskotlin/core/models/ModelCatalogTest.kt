package dev.hermeskotlin.core.models

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelCatalogTest {

    private fun parse(json: String) = ModelCatalog.parse(Json.parseToJsonElement(json) as JsonObject)

    @Test
    fun keepsAuthenticatedProvidersWithTheirCapabilitiesAndPrices() {
        val catalog = parse(
            """{"model":"gpt-6","provider":"openai","providers":[
              {"slug":"openai","name":"OpenAI","models":["gpt-6","gpt-6-mini","old"],"unavailable_models":["old"],
               "featured_models":["gpt-6","missing"],
               "capabilities":{"gpt-6":{"fast":true,"reasoning":true,"can_disable_reasoning":false},
                               "gpt-6-mini":{"fast":false,"reasoning":true,"can_disable_reasoning":true}},
               "pricing":{"gpt-6":{"input":"${'$'}1.25","output":"${'$'}10","free":false},"gpt-6-mini":{"input":"","output":"","free":true}}},
              {"slug":"anthropic","name":"Anthropic","models":["claude"],"authenticated":false},
              {"slug":"empty","name":"Empty","models":[]}
            ]}""",
        )
        assertEquals("gpt-6", catalog.currentModel)
        assertEquals(listOf("openai"), catalog.providers.map { it.slug })
        val openai = catalog.providers.single()
        assertEquals(listOf("gpt-6", "gpt-6-mini"), openai.models.map { it.id })
        assertEquals(listOf("gpt-6"), openai.featured)
        val main = catalog.find("openai", "gpt-6")!!
        assertTrue(main.fast && main.reasoning && !main.canDisableReasoning)
        assertEquals("${'$'}1.25 / ${'$'}10", main.price)
        assertEquals("Free", catalog.find(null, "gpt-6-mini")!!.price)
        assertNull(catalog.find("anthropic", "claude"))
    }

    @Test
    fun aLocalProviderWithModelsAndNoPriceStaysSelectable() {
        val catalog = parse(
            """{"providers":[
              {"slug":"llamacpp","name":"Local","models":["qwen"],"authenticated":false,"auth_type":"local"},
              {"slug":"ollama","name":"Ollama","models":["llama3"],"authenticated":false},
              {"slug":"lmstudio","name":"LM Studio","models":["qwen-local"],"authenticated":false,"auth_type":"api_key","key_env":"LM_API_KEY"},
              {"slug":"custom:box","name":"My GPU","models":["box-model"],"authenticated":false,"is_user_defined":true},
              {"slug":"my-gpu","name":"Bench","models":["bench"],"authenticated":false,"is_user_defined":true},
              {"slug":"runtime","name":"Local","models":["gguf"],"authenticated":true},
              {"slug":"anthropic","name":"Anthropic","models":["claude"],"authenticated":false},
              {"slug":"ollama-cloud","name":"Ollama Cloud","models":["cloud"],"authenticated":false,"auth_type":"api_key","key_env":"OLLAMA_API_KEY"},
              {"slug":"proxy","name":"Proxy","models":["paid"],"authenticated":false,"auth_type":"api_key","key_env":"PROXY_KEY","is_user_defined":true},
              {"slug":"empty-local","name":"Local","models":[],"authenticated":false,"auth_type":"local"}
            ]}""",
        ).withChosen(emptySet())
        val usable = catalog.usable()
        assertEquals(
            listOf("llamacpp", "ollama", "lmstudio", "custom:box", "my-gpu", "runtime"),
            usable.providers.map { it.slug },
        )
        assertTrue(usable.providers.all { it.chosen && it.keyless && it.models.single().price == null })
        val shown = usable.pickerSections("", PickerScope.All).flatMap { section -> section.models.flatMap { row -> row.variants.map { it.id } } }
        assertEquals(listOf("qwen", "llama3", "qwen-local", "box-model", "bench", "gguf"), shown)
        assertNull(catalog.find("anthropic", "claude"))
        assertNull(catalog.find("ollama-cloud", "cloud"))
        assertNull(catalog.find("proxy", "paid"))
        assertNull(catalog.find("empty-local", "missing"))
    }

    @Test
    fun pricesFromARefreshFillTheGaps() {
        val priced = ModelCatalog(
            listOf(
                ModelProvider("openrouter", "OpenRouter", listOf(ModelOption("a/m1", "openrouter", price = "$1 / $2"), ModelOption("a/m2", "openrouter"))),
                ModelProvider("nous", "Nous Portal", listOf(ModelOption("a/m1", "nous", price = "$3 / $4"))),
            ),
        )
        assertFalse(priced.missingPrices)
        assertEquals(mapOf("openrouter/a/m1" to "$1 / $2", "nous/a/m1" to "$3 / $4"), priced.prices())

        val bare = ModelCatalog(
            listOf(
                ModelProvider("openrouter", "OpenRouter", listOf(ModelOption("a/m1", "openrouter"), ModelOption("a/m2", "openrouter"))),
                ModelProvider("nous", "Nous Portal", listOf(ModelOption("a/m1", "nous", price = "Free"))),
            ),
        )
        // Nous has a price, OpenRouter none: one priced provider doesn't hide another's missing prices.
        assertTrue(bare.missingPrices)
        val filled = bare.withPrices(priced.prices())
        assertEquals("$1 / $2", filled.find("openrouter", "a/m1")!!.price)
        assertNull(filled.find("openrouter", "a/m2")!!.price)
        // A price the catalog has already stays: the same id on another provider doesn't override it.
        assertEquals("Free", filled.find("nous", "a/m1")!!.price)
    }

    @Test
    fun providersTheGatewayNeverPricesDontCount() {
        val subscription = ModelCatalog(listOf(ModelProvider("openai-codex", "OpenAI Codex", listOf(ModelOption("gpt-6", "openai-codex")))))
        assertFalse(subscription.missingPrices)
    }

    @Test
    fun aRepeatedProviderSlugIsKeptOnce() {
        val catalog = parse(
            """{"providers":[
                {"slug":"custom","name":"First","models":["m1"]},
                {"slug":"custom","name":"Second","models":["m1","m2"]}
            ]}""",
        )
        assertEquals(listOf("First"), catalog.providers.map { it.name })
    }

    @Test
    fun effortChoicesFollowTheModel() {
        val off = ModelOption("m", "p", reasoning = true, canDisableReasoning = true)
        assertEquals(ReasoningEffort.Off, ReasoningEffort.choicesFor(off).first())
        assertFalse(ReasoningEffort.Off in ReasoningEffort.choicesFor(off.copy(canDisableReasoning = false)))
        assertTrue(ReasoningEffort.choicesFor(off.copy(reasoning = false)).isEmpty())
        assertEquals(ReasoningEffort.XHigh, ReasoningEffort.fromWire(" XHIGH "))
    }
}
