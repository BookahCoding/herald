package dev.hermeskotlin.core.push

import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.profiles.ProfileStore
import dev.hermeskotlin.core.profiles.ProfilesApi
import dev.hermeskotlin.core.storage.KeyValueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Which profiles may notify this phone. Every notification in Herald belongs to a profile (a bot's reply, a cron
 * run, a room line, a chat): this is the one place that says whether it may be told. Asked wherever one would be
 * posted or kept, however it arrived.
 */
interface ProfileGate {
    /** The profiles [gateway] may notify about, or null for no limit (previews and tests only). */
    fun allowed(gateway: String?): Set<String>?

    /** Whether a notification for [profile] (null: the gateway's launch profile, `default`) may be told for [gateway]. */
    fun allows(gateway: String?, profile: String?): Boolean = allowed(gateway)?.contains(profile ?: ProfilesApi.DEFAULT) ?: true

    /** The same for the gateway in use now. */
    fun allowsHere(profile: String?): Boolean

    /**
     * Whether the profile picked on the gateway in use may notify (none picked: the launch profile, `default`).
     * For what the gateway reports without saying whose it is: the chats it lists belong to the picked profile.
     */
    suspend fun allowsSelected(): Boolean

    companion object {
        /** No limit: for previews and tests that aren't about this. */
        val AllowAll: ProfileGate = object : ProfileGate {
            override fun allowed(gateway: String?): Set<String>? = null
            override fun allowsHere(profile: String?) = true
            override suspend fun allowsSelected() = true
        }
    }
}

/**
 * The profiles this phone may be notified about, kept per gateway on the phone. A gateway with no entry gets
 * [DEFAULT_ALLOWED] only: a profile that isn't listed, whether unknown or made after the list was, never notifies.
 * Until the stored lists are read, or if they can't be, that is what applies too.
 *
 * The list also goes to the gateway's herald-push plugin ([PushSetup]), which stops those pushes before they are
 * sent; this is the check on the phone, which covers the direct connection and any push that still arrives.
 */
class NotificationProfiles(
    private val store: KeyValueStore,
    private val gateways: GatewayRepository,
    private val selected: ProfileStore,
    private val scope: CoroutineScope,
) : ProfileGate {

    private val _lists = MutableStateFlow<Map<String, List<String>>?>(null)

    /** The stored lists by gateway URL; null until read. */
    val lists: StateFlow<Map<String, List<String>>?> = _lists.asStateFlow()

    private val writes = Mutex()

    init {
        scope.launch {
            val stored = store.get(KEY)?.let { runCatching { HermesJson.decodeFromString(SERIALIZER, it) }.getOrNull() }
            // A change made before the read finished wins over the stored copy.
            _lists.compareAndSet(null, stored.orEmpty())
        }
    }

    override fun allowed(gateway: String?): Set<String> = listFor(gateway).toSet()

    override fun allowsHere(profile: String?): Boolean = allows(gatewayHere(), profile)

    override suspend fun allowsSelected(): Boolean {
        val gateway = gateways.list.value.current?.gatewayUrl ?: return allows(null, null)
        return allows(gateway.value, selected.get(gateway))
    }

    /** The profiles [gateway] may notify about, sorted. */
    fun listFor(gateway: String?): List<String> = gateway?.let { _lists.value?.get(it) } ?: DEFAULT_ALLOWED.toList()

    /** Whether [gateway] has a list of its own (else it has the default one). */
    fun hasListFor(gateway: String?): Boolean = gateway != null && _lists.value?.containsKey(gateway) == true

    /** Replaces [gateway]'s list with [profiles]. Applies at once and is saved behind. */
    fun set(gateway: String, profiles: Collection<String>) {
        val cleaned = profiles.filter { VALID.matches(it) }.distinct().sorted()
        val next = _lists.updateAndGet { (it ?: emptyMap()) + (gateway to cleaned) } ?: return
        scope.launch {
            writes.withLock {
                // Only the newest value matters if several writes queue up.
                if (_lists.value == next) store.put(KEY, HermesJson.encodeToString(SERIALIZER, next))
            }
        }
    }

    /** Lets [profile] notify on [gateway], or stops it. */
    fun allow(gateway: String, profile: String, on: Boolean) {
        val now = listFor(gateway)
        set(gateway, if (on) now + profile else now - profile)
    }

    /** The URL of the gateway in use, as the lists are keyed; null before the saved gateways are read. */
    fun gatewayHere(): String? = gateways.list.value.current?.gatewayUrl?.value

    companion object {
        /** What a gateway without a list of its own may notify about. */
        val DEFAULT_ALLOWED: Set<String> = setOf(ProfilesApi.DEFAULT)

        private const val KEY = "notify.profiles.v1"
        private val VALID = Regex("^[A-Za-z0-9_.-]{1,64}$")
        private val SERIALIZER = MapSerializer(String.serializer(), ListSerializer(String.serializer()))
    }
}
