package dev.hermeskotlin.core.push

import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.profiles.ProfileStore
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.core.storage.KeyValueStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationProfilesTest {

    private val one = "https://one.example"
    private val two = "https://two.example"

    private class World(
        val disk: KeyValueStore,
        val gateways: GatewayRepository,
        val picked: ProfileStore,
        val profiles: NotificationProfiles,
    )

    private suspend fun TestScope.world(disk: KeyValueStore = InMemoryKeyValueStore()): World {
        val gateways = GatewayRepository(InMemoryKeyValueStore()).apply {
            save(SavedGateway(one))
            save(SavedGateway(two))
            select(one)
        }
        val picked = ProfileStore(InMemoryKeyValueStore())
        val profiles = NotificationProfiles(disk, gateways, picked, backgroundScope)
        profiles.lists.filterNotNull().first()
        return World(disk, gateways, picked, profiles)
    }

    @Test
    fun withNoListOnlyTheDefaultProfileMayNotify() = runTest {
        val w = world()
        assertEquals(setOf("default"), w.profiles.allowed(one))
        assertTrue(w.profiles.allows(one, "default"))
        assertTrue(w.profiles.allows(one, null), "no profile named is the gateway's launch profile, default")
        assertFalse(w.profiles.allows(one, "froebel"))
        assertFalse(w.profiles.allows(one, "made-later"))
        // No gateway at all: the same, never everything.
        assertFalse(w.profiles.allows(null, "froebel"))
        assertTrue(w.profiles.allows(null, "default"))
    }

    @Test
    fun aListNamesExactlyWhatMayNotifyAndNothingElseDoes() = runTest {
        val w = world()
        w.profiles.set(one, listOf("default", "alice"))
        assertEquals(listOf("alice", "default"), w.profiles.listFor(one))
        assertTrue(w.profiles.allows(one, "alice"))
        assertFalse(w.profiles.allows(one, "froebel"))
        // A profile made after the list was set, or never heard of, is not on it.
        assertFalse(w.profiles.allows(one, "created-after"))
        assertFalse(w.profiles.allows(one, "Alice"), "names are matched exactly")
    }

    @Test
    fun eachGatewayHasItsOwnList() = runTest {
        val w = world()
        w.profiles.set(one, listOf("default", "alice"))
        assertTrue(w.profiles.hasListFor(one))
        assertFalse(w.profiles.hasListFor(two))
        assertEquals(setOf("default"), w.profiles.allowed(two))
        assertFalse(w.profiles.allows(two, "alice"))
    }

    @Test
    fun allowingAndStoppingOneProfileLeavesTheOthers() = runTest {
        val w = world()
        w.profiles.allow(one, "alice", on = true)
        assertEquals(listOf("alice", "default"), w.profiles.listFor(one))
        w.profiles.allow(one, "default", on = false)
        assertEquals(listOf("alice"), w.profiles.listFor(one))
        assertFalse(w.profiles.allows(one, "default"))
        // Even the default can be taken off: nothing then notifies, and the list says so.
        w.profiles.allow(one, "alice", on = false)
        assertEquals(emptyList(), w.profiles.listFor(one))
        assertTrue(w.profiles.hasListFor(one))
        assertFalse(w.profiles.allows(one, "default"))
    }

    @Test
    fun namesThatAreNotProfileNamesAreNotKept() = runTest {
        val w = world()
        w.profiles.set(one, listOf("default", "../etc", "a b", "", "x".repeat(65), "ok-name"))
        assertEquals(listOf("default", "ok-name"), w.profiles.listFor(one))
    }

    @Test
    fun theListSurvivesTheAppBeingRestarted() = runTest {
        val w = world()
        w.profiles.set(one, listOf("default", "alice"))
        advanceUntilIdle()

        val reopened = world(w.disk).profiles
        assertEquals(listOf("alice", "default"), reopened.listFor(one))
        assertFalse(reopened.allows(one, "froebel"))
        assertFalse(reopened.hasListFor(two))
    }

    @Test
    fun storedListsThatCantBeReadFallBackToTheDefaultOnly() = runTest {
        val disk = InMemoryKeyValueStore().apply { put("notify.profiles.v1", "{not json") }
        val w = world(disk)
        assertEquals(setOf("default"), w.profiles.allowed(one))
        assertFalse(w.profiles.allows(one, "alice"))
    }

    @Test
    fun untilTheStoredListsAreReadNothingButTheDefaultMayNotify() = runTest {
        val released = CompletableDeferred<Unit>()
        val inner = InMemoryKeyValueStore().apply { put("notify.profiles.v1", """{"$one":["default","alice"]}""") }
        val slow = object : KeyValueStore by inner {
            override suspend fun get(key: String): String? {
                released.await()
                return inner.get(key)
            }
        }
        val gateways = GatewayRepository(InMemoryKeyValueStore()).apply { save(SavedGateway(one)) }
        val profiles = NotificationProfiles(slow, gateways, ProfileStore(InMemoryKeyValueStore()), backgroundScope)

        // A push arriving this early for alice, whom the stored list allows, waits; it is never let through.
        assertNull(profiles.lists.value)
        assertFalse(profiles.allows(one, "alice"))
        assertFalse(profiles.allows(one, "froebel"))

        released.complete(Unit)
        profiles.lists.filterNotNull().first()
        assertTrue(profiles.allows(one, "alice"))
        assertFalse(profiles.allows(one, "froebel"))
    }

    @Test
    fun allowsHereFollowsTheGatewayInUse() = runTest {
        val w = world()
        w.profiles.set(one, listOf("default", "alice"))
        assertTrue(w.profiles.allowsHere("alice"))
        w.gateways.select(two)
        assertFalse(w.profiles.allowsHere("alice"), "the other gateway has no list of its own")
        assertTrue(w.profiles.allowsHere("default"))
        w.gateways.select(one)
        assertTrue(w.profiles.allowsHere("alice"))
    }

    @Test
    fun whatTheGatewayReportsWithoutAProfileBelongsToThePickedOne() = runTest {
        val w = world()
        w.profiles.set(one, listOf("default", "alice"))
        // None picked: the launch profile, default.
        assertTrue(w.profiles.allowsSelected())
        w.picked.set(GatewayUrl.parse(one), "froebel")
        assertFalse(w.profiles.allowsSelected())
        w.picked.set(GatewayUrl.parse(one), "alice")
        assertTrue(w.profiles.allowsSelected())
        w.picked.set(GatewayUrl.parse(one), null)
        assertTrue(w.profiles.allowsSelected())
    }

    @Test
    fun noLimitIsOnlyForTestsAndPreviews() = runTest {
        assertTrue(ProfileGate.AllowAll.allows("https://x", "anything"))
        assertTrue(ProfileGate.AllowAll.allowsHere("anything"))
        assertTrue(ProfileGate.AllowAll.allowsSelected())
        // The real one never says yes to everything.
        val w = world()
        assertFalse((w.profiles as ProfileGate).allows(one, "anything"))
    }
}
