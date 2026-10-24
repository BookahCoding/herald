package dev.hermeskotlin.core.models

import dev.hermeskotlin.core.chat.asObjectList
import dev.hermeskotlin.core.chat.boolean
import dev.hermeskotlin.core.chat.string
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.rpc.JsonRpcClient
import dev.hermeskotlin.core.rpc.RpcException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** One pickable model of a [ModelProvider], with what the gateway knows it can do. */
data class ModelOption(
    val id: String,
    val provider: String,
    /** Takes a reasoning effort level. */
    val reasoning: Boolean = false,
    /** Reasoning can be switched off entirely ("Off"). */
    val canDisableReasoning: Boolean = false,
    /** Has a priority tier ("Fast"). */
    val fast: Boolean = false,
    /** Formatted price per million tokens, e.g. "$1.25 / $10", "Free", or null when unknown. */
    val price: String? = null,
)

data class ModelProvider(
    val slug: String,
    val name: String,
    val models: List<ModelOption>,
    /** The provider's own short list, shown first when it has many models. */
    val featured: List<String> = emptyList(),
    val warning: String? = null,
    /**
     * You set this provider up yourself (the gateway's `explicit_only` list): a subscription you signed in to or
     * a key you pasted. Others are credentials the gateway found lying around (a CLI login, a free tier); the
     * picker offers only their free models.
     */
    val chosen: Boolean = true,
)

/** Costs nothing to run: the gateway prices it as free, or it is one of OpenRouter's `:free` variants. */
val ModelOption.isFree: Boolean get() = price == "Free" || id.endsWith(":free", ignoreCase = true)

/** `model.options`: the configured providers and what the session (or the profile default) runs now. */
data class ModelCatalog(
    val providers: List<ModelProvider>,
    val currentModel: String? = null,
    val currentProvider: String? = null,
) {
    fun find(provider: String?, model: String?): ModelOption? {
        if (model.isNullOrBlank()) return null
        val candidates = providers.filter { provider.isNullOrBlank() || it.slug == provider }
        return candidates.firstNotNullOfOrNull { p -> p.models.firstOrNull { it.id == model } }
    }

    /**
     * A provider the gateway can price (it fetches prices only for [PRICED_PROVIDERS]) came without any: its
     * prices weren't in the gateway's cache.
     */
    val missingPrices: Boolean
        get() = providers.any { p -> p.slug.lowercase() in PRICED_PROVIDERS && p.models.none { it.price != null } }

    /**
     * What the picker offers: every model of a [ModelProvider.chosen] provider, and only the free ones of the
     * rest. A provider left with nothing is dropped.
     */
    fun usable(): ModelCatalog = copy(
        providers = providers.mapNotNull { p ->
            if (p.chosen) p else p.copy(models = p.models.filter { it.isFree }).takeIf { it.models.isNotEmpty() }
        },
    )

    /** This catalog with only the providers in [slugs] marked [ModelProvider.chosen]; null marks them all. */
    fun withChosen(slugs: Set<String>?): ModelCatalog =
        copy(providers = providers.map { it.copy(chosen = slugs == null || it.slug in slugs) })

    /** Each priced model's price, by [starKey]. */
    fun prices(): Map<String, String> =
        providers.flatMap { p -> p.models.mapNotNull { m -> m.price?.let { m.starKey to it } } }.toMap()

    /** This catalog with [prices] (by [starKey]) filled in where a model has none. */
    fun withPrices(prices: Map<String, String>): ModelCatalog = if (prices.isEmpty()) this else copy(
        providers = providers.map { p -> p.copy(models = p.models.map { m -> if (m.price != null) m else m.copy(price = prices[m.starKey]) }) },
    )

    companion object {
        /** Lenient: providers without credentials or without models are dropped, unknown fields ignored. */
        fun parse(result: JsonObject?): ModelCatalog {
            val providers = result?.get("providers").asObjectList().mapNotNull { row ->
                val slug = row.string("slug") ?: return@mapNotNull null
                if (row.boolean("authenticated") == false) return@mapNotNull null
                val capabilities = row["capabilities"] as? JsonObject
                val pricing = row["pricing"] as? JsonObject
                val unavailable = row.strings("unavailable_models").toSet()
                val models = row.strings("models").filter { it !in unavailable }.distinct().map { id ->
                    val caps = capabilities?.get(id) as? JsonObject
                    ModelOption(
                        id = id,
                        provider = slug,
                        reasoning = caps.boolean("reasoning") == true,
                        canDisableReasoning = caps.boolean("can_disable_reasoning") == true,
                        fast = caps.boolean("fast") == true,
                        price = (pricing?.get(id) as? JsonObject).formatPrice(),
                    )
                }
                if (models.isEmpty()) return@mapNotNull null
                ModelProvider(
                    slug = slug,
                    name = row.string("name")?.takeIf { it.isNotBlank() } ?: slug,
                    models = models,
                    featured = row.strings("featured_models").filter { f -> models.any { it.id == f } },
                    warning = row.string("warning")?.takeIf { it.isNotBlank() },
                )
            }.distinctBy { it.slug } // Picks and stars name a provider by slug: a second one could never be told apart.
            return ModelCatalog(
                providers = providers,
                currentModel = result.string("model")?.takeIf { it.isNotBlank() },
                currentProvider = result.string("provider")?.takeIf { it.isNotBlank() },
            )
        }
    }
}

/** Reasoning effort levels, weakest first, as `config.set reasoning` and `session.create` take them. */
enum class ReasoningEffort(val wire: String, val label: String) {
    Off("none", "Off"),
    Minimal("minimal", "Minimal"),
    Low("low", "Low"),
    Medium("medium", "Medium"),
    High("high", "High"),
    XHigh("xhigh", "Extra high"),
    Max("max", "Max"),
    Ultra("ultra", "Ultra"),
    ;

    companion object {
        /** The level the gateway uses when a session doesn't set one. */
        val Default = Medium

        fun fromWire(value: String?): ReasoningEffort? = entries.firstOrNull { it.wire == value?.trim()?.lowercase() }

        /** What [model] can be set to; Off only where reasoning can be disabled. */
        fun choicesFor(model: ModelOption?): List<ReasoningEffort> = when {
            model == null || !model.reasoning -> emptyList()
            model.canDisableReasoning -> entries
            else -> entries - Off
        }
    }
}

/**
 * Reads the model catalog over the live socket.
 *
 * A normal `model.options` answers from the gateway's caches and leaves out prices it hasn't fetched yet; on some
 * gateways that cache never fills, so prices never show. [missingPrices] asks once more with `refresh` (what the
 * web picker's refresh button sends), at most once per connection and profile, and later catalogs get those
 * prices too.
 */
class ModelsApi(private val connection: GatewayConnection) {

    /** Guards the fields below; never held over a request. */
    private val lock = Mutex()
    /** The connection the fields below are about: a new one starts over. */
    private var pricedOver: Any? = null
    /** Profiles (null for the default) asked with a refresh on [pricedOver], or being asked now. */
    private val asked = mutableSetOf<String?>()
    /** What those refreshes brought, by profile, by [starKey]. */
    private val prices = mutableMapOf<String?, Map<String, String>>()

    /**
     * The catalog for [runtimeSessionId] (its current model marked) or, without one, the profile default, with the
     * providers you set up yourself marked [ModelProvider.chosen]. That comes from a second `model.options` with
     * `explicit_only`, as Hermes Desktop's picker asks; a gateway too old to take it gets every provider marked.
     */
    suspend fun options(runtimeSessionId: String? = null, profile: String? = null): ModelCatalog = coroutineScope {
        val client = client()
        val explicit = async {
            try {
                request(client, runtimeSessionId, profile, refresh = false, explicitOnly = true).providers.map { it.slug }.toSet()
                    // None at all reads as a gateway that can't tell: hiding everything would leave nothing to pick.
                    .takeIf { it.isNotEmpty() }
            } catch (e: RpcException) {
                null
            }
        }
        val catalog = request(client, runtimeSessionId, profile, refresh = false).withChosen(explicit.await())
        val known = lock.withLock { if (pricedOver === client) prices[profile] else null }
        known?.let(catalog::withPrices) ?: catalog
    }

    /**
     * The prices [catalog] (from [options]) lacks, fetched with a refresh; null when it lacks none, or this connection
     * was asked for this profile before. The refresh busts the gateway's catalog cache and probes every custom
     * provider, so it can take seconds: call it when someone is picking a model, not on every load.
     *
     * A refresh that fails isn't tried again on this connection (a gateway that times out would time out on every
     * open); one that's cancelled is, since nothing came of it.
     */
    suspend fun missingPrices(catalog: ModelCatalog, runtimeSessionId: String? = null, profile: String? = null): Map<String, String>? {
        if (!catalog.missingPrices) return null
        val client = lock.withLock {
            // Read here, not before the lock: a call that waited mustn't reset the state for a newer connection.
            val client = client()
            if (pricedOver !== client) {
                pricedOver = client
                asked.clear()
                prices.clear()
            }
            if (!asked.add(profile)) return null
            client
        }
        val fetched = try {
            request(client, runtimeSessionId, profile, refresh = true).prices()
        } catch (e: CancellationException) {
            withContext(NonCancellable) { lock.withLock { if (pricedOver === client) asked.remove(profile) } }
            throw e
        }
        lock.withLock { if (pricedOver === client) prices[profile] = fetched }
        return fetched.takeIf { it.isNotEmpty() }
    }

    private fun client() = (connection.state.value as? ConnectionState.Connected)?.client
        ?: throw RpcException(0, "Not connected to the gateway.")

    private suspend fun request(
        client: JsonRpcClient,
        runtimeSessionId: String?,
        profile: String?,
        refresh: Boolean,
        explicitOnly: Boolean = false,
    ): ModelCatalog {
        val result = client.request(
            "model.options",
            buildJsonObject {
                runtimeSessionId?.let { put("session_id", it) }
                profile?.let { put("profile", it) }
                if (refresh) put("refresh", true)
                if (explicitOnly) put("explicit_only", true)
            },
        )
        return ModelCatalog.parse(result as? JsonObject)
    }
}

/**
 * The providers a gateway fetches prices for (hermes-agent `get_pricing_for_provider`); the rest never have any,
 * so their missing prices are no reason to ask again.
 */
private val PRICED_PROVIDERS = setOf("openrouter", "nous", "ai-gateway", "novita", "deepinfra", "fireworks", "kilocode")

private fun JsonObject.strings(key: String): List<String> =
    (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }

private fun JsonObject?.formatPrice(): String? {
    if (this == null) return null
    if (boolean("free") == true) return "Free"
    val input = string("input")?.takeIf { it.isNotBlank() } ?: return null
    val output = string("output")?.takeIf { it.isNotBlank() } ?: return input
    return "$input / $output"
}
