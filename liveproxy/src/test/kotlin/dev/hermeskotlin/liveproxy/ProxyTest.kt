package dev.hermeskotlin.liveproxy

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.install
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProxyTest {

    @Test
    fun internationalUrlUsesTheSingaporeDomainAndTheKeyStaysOutOfIt() {
        assertEquals(
            "wss://dashscope-intl.aliyuncs.com/api-ws/v1/realtime?model=qwen-audio-3.1-realtime-plus",
            dashScopeRealtimeUrl("ap-southeast-1", null, "qwen-audio-3.1-realtime-plus"),
        )
        assertEquals(
            "wss://llm-ws.ap-southeast-1.maas.aliyuncs.com/api-ws/v1/realtime?model=qwen-audio-3.1-realtime-plus",
            dashScopeRealtimeUrl("singapore", "llm-ws", ProxyConfig.DEFAULT_MODEL),
        )
        assertEquals(
            "wss://dashscope.aliyuncs.com/api-ws/v1/realtime?model=qwen-audio-3.1-realtime-flash",
            dashScopeRealtimeUrl("cn-beijing", null, "qwen-audio-3.1-realtime-flash"),
        )
        assertFailsWith<IllegalArgumentException> { dashScopeRealtimeUrl("us-east-1", null, "m") }
        assertEquals(
            "wss://ws.us-east-1.maas.aliyuncs.com/api-ws/v1/realtime?model=m",
            dashScopeRealtimeUrl("us-east-1", "ws", "m"),
        )
        val update = sessionUpdate("longanqian_v3.1")
        assertTrue(update.contains("\"type\":\"session.update\""))
        assertTrue(update.contains("longanqian_v3.1"))
        assertTrue(update.contains("server_vad"))
        assertFalse(update.contains("sk-"))
    }

    @Test
    fun envRequiresTheKeyAndATokenWhenThePortIsReachable() {
        assertFailsWith<IllegalArgumentException> {
            ProxyConfig.fromEnv(emptyMap())
        }.also { assertTrue(it.message!!.contains("DASHSCOPE_API_KEY")) }

        assertFailsWith<IllegalArgumentException> {
            ProxyConfig.fromEnv(mapOf("DASHSCOPE_API_KEY" to "secret"))
        }.also { assertTrue(it.message!!.contains("HERALD_LIVE_PROXY_TOKEN")) }

        val local = ProxyConfig.fromEnv(
            mapOf("DASHSCOPE_API_KEY" to "secret", "HERALD_LIVE_BIND" to "127.0.0.1"),
        )
        assertEquals(ProxyConfig.DEFAULT_MODEL, local.model)
        assertEquals("ap-southeast-1", local.region)
        assertEquals("longanqian_v3.1", local.voice)
        assertTrue(local.upstreamUrl.startsWith("wss://dashscope-intl.aliyuncs.com/"))
        assertFalse(local.upstreamUrl.contains("secret"))

        val custom = ProxyConfig.fromEnv(
            mapOf(
                "DASHSCOPE_API_KEY" to "secret",
                "HERALD_LIVE_PROXY_TOKEN" to "phone",
                "DASHSCOPE_REALTIME_MODEL" to "qwen-audio-3.1-realtime-flash",
                "DASHSCOPE_REGION" to "international",
                "DASHSCOPE_WORKSPACE_ID" to "llm-1",
            ),
        )
        assertEquals("qwen-audio-3.1-realtime-flash", custom.model)
        assertEquals(
            "llm-1.ap-southeast-1.maas.aliyuncs.com",
            custom.upstreamUrl.substringAfter("wss://").substringBefore("/"),
        )
    }

    @Test
    fun relaysAudioKeepsTheKeyAndOwnsTheSession() = testApplication {
        val seenAuth = CompletableDeferred<String?>()
        val seenUpdate = CompletableDeferred<String>()
        val seenAudio = CompletableDeferred<String>()
        var upstreamHits = 0
        val upstream = embeddedServer(ServerCIO, port = 0, host = "127.0.0.1") {
            install(WebSockets)
            routing {
                webSocket("/api-ws/v1/realtime") {
                    upstreamHits += 1
                    seenAuth.complete(call.request.headers["Authorization"])
                    send(Frame.Text("""{"type":"session.created","session":{"model":"qwen-audio-3.1-realtime-plus"}}"""))
                    seenUpdate.complete((incoming.receive() as Frame.Text).readText())
                    send(Frame.Text("""{"type":"session.updated","session":{"model":"qwen-audio-3.1-realtime-plus"}}"""))
                    seenAudio.complete((incoming.receive() as Frame.Text).readText())
                    send(Frame.Text("""{"type":"response.audio.delta","response_id":"r1","delta":"AAAA"}"""))
                }
            }
        }.start(wait = false)
        val port = upstream.engine.resolvedConnectors().single().port
        val key = "super-secret-key"
        val config = ProxyConfig(
            apiKey = key,
            region = "ap-southeast-1",
            workspaceId = null,
            model = "qwen-audio-3.1-realtime-plus",
            voice = "longanqian_v3.1",
            bindHost = "127.0.0.1",
            port = 9,
            token = "phone-token",
            upstreamUrl = "ws://127.0.0.1:$port/api-ws/v1/realtime?model=qwen-audio-3.1-realtime-plus",
        )
        val upstreamClient = HttpClient(CIO) { install(ClientWebSockets) }
        try {
            application { installQwenProxy(config, upstreamClient) }
            val health = client.get("/health").bodyAsText()
            assertTrue(health.contains("qwen-audio-3.1-realtime-plus"))
            assertFalse(key in health)

            val rejected = withTimeout(5_000) {
                runCatching {
                    createClient { install(ClientWebSockets) }.webSocket("/v1/qwen/realtime") {
                        incoming.receive()
                    }
                }
            }
            assertTrue(rejected.isFailure)
            assertEquals(0, upstreamHits)

            createClient { install(ClientWebSockets) }.webSocket(
                "/v1/qwen/realtime",
                request = { headers.append("X-Herald-Live-Token", "phone-token") },
            ) {
                val created = (incoming.receive() as Frame.Text).readText()
                assertTrue(created.contains("session.created"))
                assertFalse(key in created)
                send(Frame.Text("""{"type":"session.update","session":{"voice":"stolen"}}"""))
                send(Frame.Text("""{"type":"input_audio_buffer.append","audio":"AAAA"}"""))
                val updated = (incoming.receive() as Frame.Text).readText()
                assertTrue(updated.contains("session.updated"))
                val delta = (incoming.receive() as Frame.Text).readText()
                assertTrue(delta.contains("response.audio.delta"))
                assertFalse(key in delta)
            }
            assertEquals("Bearer $key", withTimeout(5_000) { seenAuth.await() })
            val update = withTimeout(5_000) { seenUpdate.await() }
            assertTrue(update.contains("longanqian_v3.1"))
            assertFalse(update.contains("stolen"))
            assertFalse(key in update)
            assertTrue(withTimeout(5_000) { seenAudio.await() }.contains("input_audio_buffer.append"))
        } finally {
            upstreamClient.close()
            upstream.stop()
        }
    }
}
