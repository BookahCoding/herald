package dev.hermeskotlin.core.bots

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.storage.KeyValueStore
import kotlinx.serialization.Serializable

/**
 * Profiles on a gateway the user has made into bots ([Bot.hasBotIdentity]), remembered from the last
 * roster read. Absent until a roster has been read, so a launch can tell "not a bot" from "not known yet"
 * without opening a Bot Chat to find out. Only profile names are kept.
 */
class BotOptInStore(private val store: KeyValueStore) {

    /** The opted-in profile names, or null when no roster has been recorded for [gateway]. */
    suspend fun known(gateway: GatewayUrl): Set<String>? = store.get(key(gateway))?.let { raw ->
        runCatching { HermesJson.decodeFromString(Saved.serializer(), raw).names.toSet() }.getOrNull()
    }

    /** Remembers which of [bots] are bots the user made. An empty list still counts as a roster read. */
    suspend fun record(gateway: GatewayUrl, bots: List<Bot>) {
        val names = bots.filter { it.hasBotIdentity }.map { it.name }.distinct().sorted()
        store.put(key(gateway), HermesJson.encodeToString(Saved.serializer(), Saved(names)))
    }

    private fun key(gateway: GatewayUrl) = "bots.opted-in.v1.$gateway"

    @Serializable
    private data class Saved(val names: List<String>)
}
