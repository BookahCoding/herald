package dev.hermeskotlin.liveproxy

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer

/**
 * Holds `DASHSCOPE_API_KEY` and relays the phone's live chat to DashScope.
 * Run with `./gradlew :liveproxy:run` after exporting the variables in the README.
 */
fun main() {
    val config = ProxyConfig.fromEnv(System.getenv())
    val upstream = HttpClient(CIO) {
        install(WebSockets) {
            pingIntervalMillis = 20_000
        }
    }
    println(
        "Qwen live proxy on ${config.bindHost}:${config.port} " +
            "region=${config.region} model=${config.model} voice=${config.voice} " +
            "workspace=${if (config.workspaceId != null) "set" else "legacy-domain"}",
    )
    Runtime.getRuntime().addShutdownHook(Thread { upstream.close() })
    embeddedServer(ServerCIO, host = config.bindHost, port = config.port) {
        installQwenProxy(config, upstream)
    }.start(wait = true)
}
