package com.pusios.pots.mcp

import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.net.ServerSocket
import kotlin.time.Duration

actual fun startMcpAuthListener(path: String): McpAuthListener? = try {
    LoopbackMcpAuthListener(path)
} catch (_: Exception) {
    null
}

/**
 * One-shot NanoHTTPD bound to 127.0.0.1 on an ephemeral port. The port is picked with a
 * throwaway ServerSocket first because NanoHTTPD cannot report a port it chose itself.
 */
private class LoopbackMcpAuthListener(private val path: String) : McpAuthListener {

    private val deferred = CompletableDeferred<McpAuthCallback?>()
    private val server: NanoHTTPD
    override val redirectUri: String

    init {
        val port = ServerSocket(0).use { it.localPort }
        redirectUri = "http://127.0.0.1:$port/$path"
        server = object : NanoHTTPD("127.0.0.1", port) {
            override fun serve(session: IHTTPSession): Response {
                if (session.uri.trim('/') != path) {
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
                }
                val params = session.parameters
                deferred.complete(
                    McpAuthCallback(
                        code = params["code"]?.firstOrNull(),
                        state = params["state"]?.firstOrNull(),
                        error = params["error"]?.firstOrNull(),
                        errorDescription = params["error_description"]?.firstOrNull(),
                    ),
                )
                return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", DONE_PAGE)
            }
        }
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
    }

    override suspend fun await(timeout: Duration): McpAuthCallback? = withTimeoutOrNull(timeout) { deferred.await() }

    override fun close() {
        deferred.complete(null)
        runCatching { server.stop() }
    }

    private companion object {
        // The owner sees this tab after approving; it must not look like a dead end.
        const val DONE_PAGE =
            "<!doctype html><meta charset=\"utf-8\"><title>Pots</title>" +
                "<body style=\"font-family:sans-serif;background:#111;color:#eee;padding:2rem\">" +
                "<h2>Готово</h2><p>Можно закрыть эту вкладку и вернуться в Pots.</p>"
    }
}
