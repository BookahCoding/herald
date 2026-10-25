package dev.hermeskotlin.core.inbox

import dev.hermeskotlin.core.push.ProfileGate
import dev.hermeskotlin.core.push.PushApi
import dev.hermeskotlin.core.push.PushGateway
import dev.hermeskotlin.core.push.PushKeys
import dev.hermeskotlin.core.push.PushRegistration
import dev.hermeskotlin.core.rpc.FakeGateway
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Inbox keeps only the runs of profiles that may notify this phone, however a run reaches it. */
class InboxProfilesTest {

    private val gw = "https://gw"

    /** A list of profiles that can change under the Inbox, as the user's does. */
    private class Gate(var allowed: Set<String>) : ProfileGate {
        override fun allowed(gateway: String?): Set<String> = allowed
        override fun allowsHere(profile: String?) = (profile ?: "default") in allowed
        override suspend fun allowsSelected() = true
    }

    private fun run(bot: String = "default", job: String = "brief", at: Long = 1_000, text: String = "text") =
        InboxRun(bot = bot, job = job, run = "2026-10-12_08-00-00", at = at, text = text)

    @Test
    fun aRunOfAProfileThatMayNotNotifyIsNotKeptByAnyWayItComesIn() = runTest {
        val store = InboxStore(InMemoryKeyValueStore(), gate = Gate(setOf("default")))
        store.bind(gw, now = 0)

        // Each way in on its own, looked at straight after it: a later one must not be what cleans up.
        store.receive(gw, run(bot = "froebel", job = "pushed", at = 3_000))
        assertEquals(emptyList(), store.state.value.runs.map { it.job }, "a pushed run")

        store.add(gw, run(bot = "froebel", job = "added", at = 4_000))
        assertEquals(emptyList(), store.state.value.runs.map { it.job }, "a run read in full")

        store.replace(gw, listOf(run(bot = "froebel", job = "listed", at = 5_000)), complete = true)
        assertEquals(emptyList(), store.state.value.runs.map { it.job }, "a list from the gateway")

        // The profile that may notify still gets through every one of them.
        store.receive(gw, run(bot = "default", job = "mine-pushed", at = 2_000))
        store.add(gw, run(bot = "default", job = "mine-added", at = 1_500))
        store.replace(gw, listOf(run(bot = "default", job = "mine-listed", at = 6_000), run(bot = "froebel", job = "hers", at = 7_000)), complete = true)
        assertEquals(listOf("mine-listed"), store.state.value.runs.map { it.job })
    }

    @Test
    fun aPushedRunOfAProfileNotOnTheListIsNotEvenSavedForAGatewayThatIsntShown() = runTest {
        val disk = InMemoryKeyValueStore()
        val store = InboxStore(disk, gate = Gate(setOf("default")))
        store.bind(gw, now = 0)
        // A push for another gateway than the one on screen goes to that gateway's own Inbox, and is gated there too.
        store.receive("https://other", run(bot = "froebel", job = "hers", text = "private words"))
        store.receive("https://other", run(bot = "default", job = "kept"))

        val raw = disk.get(InboxStore.keyFor("https://other")).orEmpty()
        assertTrue("kept" in raw, raw)
        assertFalse("private words" in raw || "hers" in raw || "froebel" in raw, "what was saved: $raw")
    }

    @Test
    fun runsKeptBeforeAProfileWasTakenOffTheListGoWhenTheInboxOpens() = runTest {
        val disk = InMemoryKeyValueStore()
        val before = InboxStore(disk, gate = Gate(setOf("default", "froebel")))
        before.bind(gw, now = 0)
        before.add(gw, run(bot = "froebel", job = "hers", text = "private"))
        before.add(gw, run(bot = "default", job = "mine"))
        assertEquals(2, before.state.value.runs.size)

        // The user takes froebel off the list; the next time the app starts, her runs are not on the phone.
        val after = InboxStore(disk, gate = Gate(setOf("default")))
        after.bind(gw, now = 0)
        assertEquals(listOf("mine"), after.state.value.runs.map { it.job })

        // And it was saved without them: reading the stored copy with the old list finds nothing of hers.
        val again = InboxStore(disk, gate = Gate(setOf("default", "froebel")))
        again.bind(gw, now = 0)
        assertEquals(listOf("mine"), again.state.value.runs.map { it.job })
    }

    @Test
    fun takingAProfileOffTheListWhileTheInboxIsOpenDropsItsRunsAtOnce() = runTest {
        val gate = Gate(setOf("default", "alice"))
        val store = InboxStore(InMemoryKeyValueStore(), gate = gate)
        store.bind(gw, now = 0)
        store.add(gw, run(bot = "alice", job = "a"))
        store.add(gw, run(bot = "default", job = "d"))
        assertEquals(2, store.state.value.runs.size)

        gate.allowed = setOf("default")
        store.enforce(gw)
        assertEquals(listOf("d"), store.state.value.runs.map { it.job })
    }

    @Test
    fun readAndUnreadMarksSurviveTheFiltering() = runTest {
        val store = InboxStore(InMemoryKeyValueStore(), gate = Gate(setOf("default")))
        store.bind(gw, now = 0)
        store.add(gw, run(bot = "default", job = "d", at = 10))
        store.markRead(gw, run(bot = "default", job = "d", at = 10))
        store.add(gw, run(bot = "froebel", job = "f", at = 20))
        assertEquals(0, store.state.value.unread)
        assertEquals(listOf("d"), store.state.value.runs.map { it.job })
    }

    // ── what the gateway lists or gives ─────────────────────────────────────

    private object Keys : PushKeys {
        override val deviceId = "device-1"
        override fun registration(name: String): PushRegistration = error("not registering in tests")
        override fun pinned(gatewayUrl: String): PushGateway? = null
        override fun accepts(keys: PushGateway) = false
        override fun pin(gatewayUrl: String, keys: PushGateway) = Unit
        override fun retire(gatewayUrl: String) = Unit
        override fun retired(gatewayUrl: String) = emptySet<String>()
        override fun forgetRetired(gatewayUrl: String, deviceId: String) = Unit
        override fun pinnedGatewayUrl(): String? = null
    }

    private val sent = mutableListOf<String>()

    private suspend fun TestScope.api(gate: ProfileGate, plugin: (String) -> String): InboxApi {
        val fake = FakeGateway(backgroundScope)
        fake.answer = { call ->
            if (call.method == "command.dispatch") {
                val arg = call.param("arg").orEmpty().removePrefix("cron ")
                sent += arg
                """{"type":"plugin","output":${JsonPrimitive(plugin(arg))}}"""
            } else {
                "{}"
            }
        }
        return InboxApi(PushApi(fake.start()), Keys, gate)
    }

    private val listed = """{"ok":true,"runs":[
        {"bot":"froebel","job":"brief","run":"2026-10-12_09-00-00","label":"hers","status":"ok","at":2,"text":"private"},
        {"bot":"default","job":"brief","run":"2026-10-12_08-00-00","label":"mine","status":"ok","at":1,"text":"fine"}]}""".replace("\n", "")

    @Test
    fun theListTheGatewayGivesHasOnlyTheProfilesThePhoneMayHear() = runTest {
        val api = api(Gate(setOf("default"))) { listed }
        assertEquals(listOf("default"), api.runs().map { it.bot })
    }

    @Test
    fun anOlderPluginThatListsEveryProfileIsFilteredOnThePhone() = runTest {
        // The plugin before 0.6.0 knows nothing of lists: it hands over hers too.
        val api = api(Gate(setOf("default", "froebel"))) { listed }
        assertEquals(listOf("froebel", "default"), api.runs().map { it.bot })
        val narrower = api(Gate(setOf("default"))) { listed }
        assertEquals(listOf("default"), narrower.runs().map { it.bot })
    }

    @Test
    fun readingARunOfAProfileThatMayNotNotifyAsksTheGatewayNothing() = runTest {
        val api = api(Gate(setOf("default"))) { """{"ok":true,"run":{"bot":"froebel","job":"brief","run":"2026-10-12_09-00-00","text":"private"}}""" }
        assertNull(api.read(run(bot = "froebel")))
        assertTrue(sent.isEmpty(), "nothing was asked: $sent")
        assertEquals("private", api.read(run(bot = "default"))?.text)
    }
}
