package skillatlas

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.time.Clock
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Serializable
data class ScanResponse(
    val repository: RepositoryJson,
    val skills: List<SkillJson>,
    val ignored: List<IgnoredJson>,
) {
    @Serializable
    data class RepositoryJson(val name: String, val description: String?, val branch: String, val commit: String)

    @Serializable
    data class SkillJson(
        val name: String,
        val description: String,
        @SerialName("short_description") val shortDescription: String,
        val path: String,
        @SerialName("also_at") val alsoAt: List<String>,
        val shipped: Boolean,
        val warnings: List<String>,
        val content: String?,
        @SerialName("content_html") val contentHtml: String?,
        val similar: List<SimilarJson>,
    )

    @Serializable
    data class SimilarJson(val path: String, val name: String, val score: Int)

    @Serializable
    data class IgnoredJson(val path: String, val reason: String)

    companion object {
        fun of(result: ScanResult): ScanResponse {
            val similar = SkillSimilarity.compute(result.skills, result.contents)
            return ScanResponse(
                RepositoryJson(
                    name = result.repository.fullName,
                    description = result.repository.description,
                    branch = result.branch,
                    commit = result.commit,
                ),
                result.skills.map {
                    val content = result.contents[it.path]
                    SkillJson(
                        it.name, it.description, shortenDescription(it.description), it.path, it.alsoAt, it.shipped, it.warnings,
                        content, content?.let(SkillMarkdown::render),
                        similar.getValue(it.path).map { s -> SimilarJson(s.path, s.name, s.score) },
                    )
                },
                result.ignored.map { IgnoredJson(it.path, it.reason) },
            )
        }
    }
}

@Serializable
data class ErrorResponse(val error: String, @SerialName("exit_code") val exitCode: Int)

/**
 * The local web view (spec section 5.4): a static page plus `GET /api/scan?url=…`, served
 * on 127.0.0.1 only and backed by the same [Scanner] and [ScanLog] as the CLI.
 */
class WebServer(
    private val scanner: Scanner,
    private val scanLog: ScanLog,
    private val clock: Clock,
    private val warn: (String) -> Unit,
) {
    private val json = Json { encodeDefaults = true }

    /** A running server. [port] is the actual port, which matters when 0 was requested. */
    class Running(private val server: HttpServer, private val executor: ExecutorService) : AutoCloseable {
        val port: Int get() = server.address.port
        val url: String get() = "http://127.0.0.1:$port/"

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    fun start(port: Int): Running {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0)
        val executor = Executors.newFixedThreadPool(4)
        server.executor = executor
        server.createContext("/") { exchange -> exchange.use { handle(it, server.address.port) } }
        server.start()
        return Running(server, executor)
    }

    private fun handle(exchange: HttpExchange, port: Int) {
        // Only answer requests addressed to this machine, which blocks DNS-rebinding attacks from web pages.
        val host = exchange.requestHeaders.getFirst("Host")
        if (host != "127.0.0.1:$port" && host != "localhost:$port") {
            return sendText(exchange, 403, "Forbidden")
        }
        if (exchange.requestMethod != "GET") {
            exchange.responseHeaders.add("Allow", "GET")
            return sendText(exchange, 405, "Method Not Allowed")
        }
        when (val path = exchange.requestURI.path) {
            "/api/scan" -> scan(exchange)
            else -> {
                val asset = ASSETS[path] ?: return sendText(exchange, 404, "Not Found")
                val bytes = javaClass.getResourceAsStream("/web/${asset.file}")!!.use { it.readBytes() }
                send(exchange, 200, asset.contentType, bytes)
            }
        }
    }

    private fun scan(exchange: HttpExchange) {
        val url = queryParameter(exchange, "url")
        if (url.isNullOrBlank()) {
            return sendJson(exchange, 400, json.encodeToString(ErrorResponse("missing query parameter: url", ExitCode.USAGE)))
        }
        val result = try {
            scanner.scan(url)
        } catch (e: SkillAtlasException) {
            return sendJson(exchange, statusFor(e.exitCode), json.encodeToString(ErrorResponse(e.message.orEmpty(), e.exitCode)))
        } catch (e: Exception) {
            val error = ErrorResponse("unexpected failure: ${e.message ?: e.javaClass.name}", ExitCode.INTERNAL_ERROR)
            return sendJson(exchange, 500, json.encodeToString(error))
        }
        try {
            scanLog.append(ScanLogEntry.of(result, clock.instant()))
        } catch (e: Exception) {
            warn("warning: could not write scan log ${scanLog.file}: ${e.message}")
        }
        sendJson(exchange, 200, json.encodeToString(ScanResponse.of(result)))
    }

    private fun queryParameter(exchange: HttpExchange, name: String): String? =
        exchange.requestURI.rawQuery?.split('&')?.firstNotNullOfOrNull { pair ->
            val key = pair.substringBefore('=')
            if (URLDecoder.decode(key, Charsets.UTF_8) == name) URLDecoder.decode(pair.substringAfter('=', ""), Charsets.UTF_8) else null
        }

    private fun sendJson(exchange: HttpExchange, status: Int, body: String) =
        send(exchange, status, "application/json; charset=utf-8", body.toByteArray())

    private fun sendText(exchange: HttpExchange, status: Int, body: String) =
        send(exchange, status, "text/plain; charset=utf-8", body.toByteArray())

    private fun send(exchange: HttpExchange, status: Int, contentType: String, body: ByteArray) {
        exchange.responseHeaders.apply {
            add("Content-Type", contentType)
            add("Cache-Control", "no-store")
            add("X-Content-Type-Options", "nosniff")
            add("Content-Security-Policy", "default-src 'self'; frame-ancestors 'none'")
        }
        exchange.sendResponseHeaders(status, body.size.toLong())
        exchange.responseBody.write(body)
    }

    private class Asset(val file: String, val contentType: String)

    private companion object {
        val ASSETS = mapOf(
            "/" to Asset("index.html", "text/html; charset=utf-8"),
            "/app.js" to Asset("app.js", "text/javascript; charset=utf-8"),
            "/style.css" to Asset("style.css", "text/css; charset=utf-8"),
        )

        /** HTTP status for each exit code in spec section 7. */
        fun statusFor(exitCode: Int) = when (exitCode) {
            ExitCode.USAGE -> 400
            ExitCode.REPOSITORY_NOT_FOUND -> 404
            ExitCode.BRANCH_NOT_FOUND -> 422
            ExitCode.NETWORK -> 502
            else -> 500
        }
    }
}
