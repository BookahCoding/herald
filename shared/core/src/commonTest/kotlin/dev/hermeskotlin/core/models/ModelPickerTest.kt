package dev.hermeskotlin.core.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelPickerTest {

    private val catalog = ModelCatalog(
        providers = listOf(
            provider("nous", "Nous Portal", "anthropic/claude-opus-5.5", "deepseek/deepseek-v4"),
            provider("openrouter", "OpenRouter", "anthropic/claude-opus-5.5", "deepseek/deepseek-v4", "z-ai/glm-5.2", "openai/gpt-oss-120b", "qwen/qwen3-max"),
            provider("anthropic", "Anthropic", "claude-opus-5-5", "claude-sonnet-5"),
            provider("openai-codex", "OpenAI Codex", "gpt-6-sol"),
        ),
    )

    @Test
    fun theSameModelFromSeveralProvidersIsOneRow() {
        val rows = catalog.pickerModels()
        assertEquals(listOf("Opus 5.5", "DeepSeek V4", "GLM 5.2", "GPT-oss-120b", "Qwen3 Max", "Sonnet 5", "GPT-6-sol"), rows.map { it.name })
        assertEquals(listOf("nous", "openrouter", "anthropic"), rows.first().variants.map { it.provider })
    }

    @Test
    fun twoModelsOfOneProviderStayApart() {
        val catalog = ModelCatalog(listOf(provider("openrouter", "OpenRouter", "meta/llama-5", "nous/llama-5")))
        assertEquals(2, catalog.pickerModels().size)
    }

    @Test
    fun providerScopeListsOnlyThatProvider() {
        val rows = catalog.pickerSections("", PickerScope.Provider("anthropic")).single().models
        assertEquals(listOf("Opus 5.5", "Sonnet 5"), rows.map { it.name })
        assertTrue(rows.all { r -> r.variants.all { it.provider == "anthropic" } })
    }

    @Test
    fun yoursListsStarredThenCurrentAndRecent() {
        val sections = catalog.pickerSections(
            "",
            PickerScope.Yours,
            starred = listOf("openrouter/z-ai/glm-5.2", "anthropic/claude-opus-5-5"),
            recent = listOf("anthropic/claude-sonnet-5", "nous/anthropic/claude-opus-5.5", "gone/old-model"),
            current = "openai-codex/gpt-6-sol",
        )
        assertEquals(listOf("Starred", "Recent"), sections.map { it.title })
        assertEquals(listOf("GLM 5.2", "Opus 5.5"), sections[0].models.map { it.name })
        // Opus is starred (from another provider), so it isn't repeated under Recent; a model that's gone is skipped.
        assertEquals(listOf("GPT-6-sol", "Sonnet 5"), sections[1].models.map { it.name })
    }

    @Test
    fun yoursIsEmptyWithNothingStarredOrRecent() {
        assertTrue(catalog.pickerSections("", PickerScope.Yours).isEmpty())
        assertFalse(catalog.offersAny(listOf("gone/old-model")))
        assertTrue(catalog.offersAny(listOf("anthropic/claude-sonnet-5")))
    }

    @Test
    fun searchRanksNameStartsFirst() {
        val hits = catalog.pickerSections("sonnet", PickerScope.All).single().models
        assertEquals(listOf("Sonnet 5"), hits.map { it.name })
        assertEquals(listOf("Opus 5.5"), catalog.pickerSections("opus 5.5", PickerScope.All).single().models.map { it.name })
        // "max" is a word inside "Qwen3 Max": found, after anything that starts with it.
        assertEquals(listOf("Qwen3 Max"), catalog.pickerSections("max", PickerScope.All).single().models.map { it.name })
    }

    @Test
    fun digitsMatchLettersInOrder() {
        assertEquals("Opus 5.5", catalog.pickerSections("op55", PickerScope.All).first().models.first().name)
        assertEquals("Opus 5.5", catalog.pickerSections("opus5.5", PickerScope.All).first().models.first().name)
    }

    @Test
    fun providerNameAddsItsOtherModelsAfterTheNameMatches() {
        // "open" matches the gpt-oss id (openai/…) by name, and OpenRouter and OpenAI Codex by provider name.
        val sections = catalog.pickerSections("open", PickerScope.All)
        assertEquals(listOf("Models", "From OpenRouter", "From ChatGPT subscription"), sections.map { it.title })
        assertEquals(listOf("GPT-oss-120b"), sections[0].models.map { it.name })
        assertEquals(listOf("Opus 5.5", "DeepSeek V4", "GLM 5.2", "Qwen3 Max"), sections[1].models.map { it.name })
        assertEquals(listOf("GPT-6-sol"), sections[2].models.map { it.name })
    }

    @Test
    fun aProviderNameIsNotReadAsAMisspelledModel() {
        // "open" is in "anthropic/claude-sonnet" letter by letter (o-p-e-n across the vendor prefix): not a match.
        val catalog = ModelCatalog(listOf(provider("openrouter", "OpenRouter", "anthropic/claude-sonnet-5", "qwen/qwen3-max")))
        assertEquals(listOf("From OpenRouter"), catalog.pickerSections("open", PickerScope.All).map { it.title })
        // Without a provider to name, letters in order still find a model, but only within its own name.
        assertEquals(listOf("Qwen3 Max"), catalog.pickerSections("qmax", PickerScope.All).single().models.map { it.name })
        assertTrue(catalog.pickerSections("aclsn", PickerScope.All).isEmpty())
    }

    @Test
    fun providerNameMatchesOnlyAtTheStartOfAWord() {
        // "router" starts a word nowhere: "OpenRouter" is one word.
        assertTrue(catalog.pickerSections("router", PickerScope.All).isEmpty())
        assertEquals(listOf("From Nous Portal"), catalog.pickerSections("portal", PickerScope.All).map { it.title })
    }

    @Test
    fun searchStaysInsideAProviderScope() {
        val sections = catalog.pickerSections("opus", PickerScope.Provider("anthropic"))
        assertEquals(listOf("anthropic"), sections.single().models.single().variants.map { it.provider })
        assertTrue(catalog.pickerSections("nous", PickerScope.Provider("anthropic")).isEmpty())
    }

    @Test
    fun aTapPicksTheProviderInUseThenStarredThenRecent() {
        val opus = catalog.pickerModels().first()
        assertEquals("openrouter", opus.preferred("openrouter", "anthropic/claude-opus-5.5", emptyList(), emptyList()).provider)
        assertEquals("anthropic", opus.preferred(null, null, listOf("anthropic/claude-opus-5-5"), emptyList()).provider)
        assertEquals("openrouter", opus.preferred(null, null, emptyList(), listOf("openrouter/anthropic/claude-opus-5.5")).provider)
        assertEquals("nous", opus.preferred(null, null, emptyList(), emptyList()).provider)
    }

    @Test
    fun unstarringARowUnstarsEveryProvider() {
        val opus = catalog.pickerModels().first()
        val starred = emptyList<String>().toggleStar(opus, opus.variants[1])
        assertEquals(listOf("openrouter/anthropic/claude-opus-5.5"), starred)
        assertTrue(opus.isStarred(starred))
        assertEquals(emptyList(), (starred + "nous/anthropic/claude-opus-5.5").toggleStar(opus, opus.variants[0]))
    }

    @Test
    fun recentMovesToTheFrontAndKeepsTheLastFew() {
        val models = catalog.pickerModels().flatMap { it.variants }
        val recent = models.fold(emptyList<String>()) { list, m -> list.withRecent(m) }
        assertEquals(8, recent.size)
        assertEquals(models.last().starKey, recent.first())
        assertEquals(listOf(models[0].starKey) + recent.take(7), recent.withRecent(models[0]))
    }

    @Test
    fun aQueryCanMatchModelIdsAndAProvider() {
        // OpenRouter's anthropic/… ids match "anthropic" by name; Anthropic's own models come after, as its provider's.
        val sections = catalog.pickerSections("anthropic", PickerScope.All)
        assertEquals(listOf("Models", "From Anthropic"), sections.map { it.title })
        assertEquals(listOf("Opus 5.5"), sections[0].models.map { it.name })
        assertEquals(listOf("Sonnet 5"), sections[1].models.map { it.name })
    }

    @Test
    fun anEmptyCatalogHasNothingToShow() {
        val empty = ModelCatalog(emptyList())
        assertTrue(empty.pickerSections("", PickerScope.All).isEmpty())
        assertTrue(empty.pickerSections("opus", PickerScope.All).isEmpty())
        assertFalse(empty.offersAny(listOf("anthropic/claude-opus-5-5")))
    }

    @Test
    fun theScopeFallsBackAndASearchIsNeverOfYours() {
        assertEquals(PickerScope.All, pickerScope(null, hasYours = true, searching = false))
        assertEquals(PickerScope.Yours, pickerScope(PickerScope.Yours, hasYours = true, searching = false))
        assertEquals(PickerScope.All, pickerScope(null, hasYours = false, searching = false))
        // Your models chosen, then the last star removed: nothing of yours left to show.
        assertEquals(PickerScope.All, pickerScope(PickerScope.Yours, hasYours = false, searching = false))
        assertEquals(PickerScope.All, pickerScope(PickerScope.Yours, hasYours = true, searching = true))
        assertEquals(PickerScope.Provider("nous"), pickerScope(PickerScope.Provider("nous"), hasYours = true, searching = true))
        assertEquals(PickerScope.All, pickerScope(PickerScope.All, hasYours = true, searching = false))
    }

    private val mixed = ModelCatalog(
        providers = listOf(
            ModelProvider("openai-codex", "OpenAI Codex", listOf(ModelOption("gpt-6-sol", "openai-codex"), ModelOption("gpt-6-mini", "openai-codex"))),
            ModelProvider("alibaba", "Qwen Cloud", listOf(ModelOption("qwen3.6-plus", "alibaba"), ModelOption("qwen3-coder-free", "alibaba", price = "Free"))),
            // Found by the gateway, not set up: only its free models are offered.
            ModelProvider(
                "openrouter", "OpenRouter",
                listOf(ModelOption("z-ai/glm-5.2", "openrouter", price = "$1 / $2"), ModelOption("deepseek/deepseek-v4:free", "openrouter")),
                chosen = false,
            ),
            ModelProvider("nous", "Nous Portal", listOf(ModelOption("anthropic/claude-opus-5.5", "nous")), chosen = false),
        ),
    ).usable()

    @Test
    fun onlyTheFreeModelsOfProvidersYouDidntSetUpAreOffered() {
        assertEquals(listOf("openai-codex", "alibaba", "openrouter"), mixed.providers.map { it.slug })
        assertEquals(listOf("deepseek/deepseek-v4:free"), mixed.providers.last().models.map { it.id })
    }

    @Test
    fun allListsEachProviderYouSetUpThenTheFreeOnes() {
        val sections = mixed.pickerSections("", PickerScope.All)
        assertEquals(listOf("ChatGPT subscription", "Qwen Cloud", FREE_SECTION), sections.map { it.title })
        assertEquals(listOf("openai-codex", "alibaba", null), sections.map { it.provider })
        assertEquals(listOf("GPT-6-sol", "GPT-6-mini"), sections[0].models.map { it.name })
        assertEquals(listOf("qwen3.6-plus"), sections[1].models.flatMap { r -> r.variants.map { it.id } })
        assertEquals(listOf("qwen3-coder-free", "deepseek/deepseek-v4:free"), sections[2].models.flatMap { r -> r.variants.map { it.id } })
        assertEquals(sections[2].models, mixed.pickerSections("", PickerScope.Free).single().models)
    }

    @Test
    fun aSearchFindsOnlyWhatIsOffered() {
        assertTrue(mixed.pickerSections("glm", PickerScope.All).isEmpty())
        assertTrue(mixed.pickerSections("opus", PickerScope.All).isEmpty())
        assertEquals(listOf("DeepSeek V4:free"), mixed.pickerSections("deepseek", PickerScope.All).single().models.map { it.name })
    }

    @Test
    fun withoutFreeModelsThereIsNoFreeSection() {
        assertEquals(listOf("Nous Portal", "OpenRouter", "Anthropic", "ChatGPT subscription"), catalog.pickerSections("", PickerScope.All).map { it.title })
    }

    private val withLocal = ModelCatalog(
        providers = listOf(
            provider("nous", "Nous Portal", "anthropic/claude-opus-5.5"),
            ModelProvider("ollama", "Ollama", listOf(ModelOption("llama3", "ollama")), keyless = true),
            ModelProvider("custom", "Custom", listOf(ModelOption("qwen-local", "custom", price = "Free")), keyless = true),
        ),
    ).usable()

    @Test
    fun localAndCustomProvidersShareOneLocalSection() {
        val sections = withLocal.pickerSections("", PickerScope.All)
        assertEquals(listOf("Nous Portal", LOCAL_SECTION), sections.map { it.title })
        assertEquals(listOf("ollama", "custom"), sections[1].models.map { it.provider })
        // A local model the gateway priced free stays in the Local section, not the free one.
        assertEquals(listOf("llama3", "qwen-local"), sections[1].models.flatMap { row -> row.variants.map { it.id } })
        assertEquals(
            sections[1].models.flatMap { row -> row.variants.map { it.id } },
            withLocal.pickerSections("", PickerScope.Local).single().models.flatMap { row -> row.variants.map { it.id } },
        )
        // The Free chip is a plain filter over every model, so the free-priced local one appears there too.
        assertEquals(listOf("qwen-local"), withLocal.pickerSections("", PickerScope.Free).single().models.flatMap { row -> row.variants.map { it.id } })
    }

    @Test
    fun aLocalOnlyCatalogHasJustTheLocalSection() {
        val local = withLocal.copy(providers = withLocal.providers.filter { it.keyless })
        val sections = local.pickerSections("", PickerScope.All)
        assertEquals(listOf(LOCAL_SECTION), sections.map { it.title })
        assertEquals(listOf("llama3", "qwen-local"), sections.single().models.flatMap { row -> row.variants.map { it.id } })
    }

    @Test
    fun aSearchInAllStillFindsLocalModelsButTheyAreTagged() {
        val sections = withLocal.pickerSections("llama", PickerScope.All)
        assertEquals(listOf("Llama3"), sections.single().models.map { it.name })
    }

    private fun provider(slug: String, name: String, vararg ids: String) = ModelProvider(slug, name, ids.map { ModelOption(id = it, provider = slug) })
}
