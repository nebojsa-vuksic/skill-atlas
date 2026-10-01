package skillatlas

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.time.Clock
import java.time.Duration
import java.time.Instant
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

/** `GET /api/scans`: several repositories and owners scanned together (spec sections 5.10 and 5.11). */
object MultiScanResponse {
    @Serializable
    data class SkillJson(
        val repository: String,
        val id: String,
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
    data class SimilarJson(val repository: String, val id: String, val path: String, val name: String, val score: Int)

    @Serializable
    data class IgnoredJson(val repository: String, val path: String, val reason: String)

    private val json = Json { encodeDefaults = true }

    fun of(scan: MultiScan): String {
        val outcomes = scan.reported
        val results = outcomes.filterIsInstance<RepositoryOutcome.Scanned>().map { it.result }
        val similar = crossRepositorySimilarity(results)
        // Built by hand so a scanned repository keeps "description": null while a failed one has only its error.
        val repositories = buildJsonArray {
            for (outcome in outcomes) add(
                buildJsonObject {
                    put("url", outcome.url)
                    outcome.from?.let { put("from", it) }
                    when (outcome) {
                        is RepositoryOutcome.Scanned -> {
                            val result = outcome.result
                            put("name", result.repository.fullName)
                            put("description", result.repository.description)
                            put("branch", result.branch)
                            put("commit", result.commit)
                            put("skill_count", result.skills.size)
                        }
                        is RepositoryOutcome.Failed -> {
                            put("name", outcome.label)
                            put("error", outcome.error.message)
                            put("exit_code", outcome.error.exitCode)
                        }
                    }
                },
            )
        }
        val skills = results.flatMap { result ->
            val repository = result.repository.fullName
            result.skills.map { skill ->
                val id = skillId(repository, skill.path)
                val content = result.contents[skill.path]
                SkillJson(
                    repository, id, skill.name, skill.description, shortenDescription(skill.description), skill.path,
                    skill.alsoAt, skill.shipped, skill.warnings, content, content?.let(SkillMarkdown::render),
                    similar.getValue(id).map { other ->
                        SimilarJson(other.path.substringBefore(':'), other.path, other.path.substringAfter(':'), other.name, other.score)
                    },
                )
            }
        }
        val owners = buildJsonArray {
            for (owner in scan.owners) add(
                buildJsonObject {
                    put("url", owner.url)
                    put("name", owner.label)
                    when (owner) {
                        is OwnerOutcome.Searched -> {
                            put("type", owner.discovery.owner.type)
                            put("repository_count", owner.discovery.checked)
                            put("skipped", owner.discovery.skipped)
                            put("repositories", buildJsonArray { owner.withSkills.forEach { add(JsonPrimitive(it)) } })
                            put("summary", owner.line)
                        }
                        is OwnerOutcome.Failed -> {
                            put("error", owner.error.message)
                            put("exit_code", owner.error.exitCode)
                        }
                    }
                },
            )
        }
        val ignored = results.flatMap { result -> result.ignored.map { IgnoredJson(result.repository.fullName, it.path, it.reason) } }
        return buildJsonObject {
            put("repositories", repositories)
            put("owners", owners)
            put("skills", json.encodeToJsonElement(skills))
            put("ignored", json.encodeToJsonElement(ignored))
        }.toString()
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
    private val cache = ScanCache()

    /** Owners' repository lists, so loading an owner again makes no GitHub request (spec section 5.11). */
    private val owners = ExpiringCache<OwnerDiscovery>()

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
            "/api/scans" -> scanMany(exchange)
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

    private fun scanMany(exchange: HttpExchange) {
        val urls = queryParameters(exchange, "url").filter { it.isNotBlank() }
        if (urls.isEmpty() || urls.size > MAX_REPOSITORIES) {
            val error = ErrorResponse("pass between 1 and $MAX_REPOSITORIES url query parameters", ExitCode.USAGE)
            return sendJson(exchange, 400, json.encodeToString(error))
        }
        val fresh = mutableListOf<ScanResult>()
        val scan = try {
            MultiScanner(scanner).scanAllWith(
                urls,
                discover = { login -> owners.get(login, clock.instant()) ?: scanner.discoverOwner(login).also { owners.put(login, it, clock.instant()) } },
            ) { url, listed ->
                cache.get(url, clock.instant())
                    ?: (if (listed == null) scanner.scan(url) else scanner.scan(listed)).also { synchronized(fresh) { fresh += it } }
            }
        } catch (e: Exception) {
            val error = ErrorResponse("unexpected failure: ${e.message ?: e.javaClass.name}", ExitCode.INTERNAL_ERROR)
            return sendJson(exchange, 500, json.encodeToString(error))
        }
        // Only repositories that were really scanned now are cached and logged, not cache hits.
        for (result in synchronized(fresh) { fresh.toList() }) {
            cache.put(result, clock.instant())
            try {
                scanLog.append(ScanLogEntry.of(result, clock.instant()))
            } catch (e: Exception) {
                warn("warning: could not write scan log ${scanLog.file}: ${e.message}")
            }
        }
        sendJson(exchange, 200, MultiScanResponse.of(scan))
    }

    private fun queryParameters(exchange: HttpExchange, name: String): List<String> =
        exchange.requestURI.rawQuery?.split('&').orEmpty().mapNotNull { pair ->
            val key = pair.substringBefore('=')
            if (URLDecoder.decode(key, Charsets.UTF_8) == name) URLDecoder.decode(pair.substringAfter('=', ""), Charsets.UTF_8) else null
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

        const val MAX_REPOSITORIES = 10

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

/** Recent scan results for `/api/scans`, so adding a repository doesn't clone the others again (spec section 5.10). */
class ScanCache(ttl: Duration = Duration.ofMinutes(10)) {
    private val results = ExpiringCache<ScanResult>(ttl)

    fun get(url: String, now: Instant): ScanResult? {
        val key = runCatching { GitHubUrl.parse(url).toString() }.getOrNull() ?: return null
        return results.get(key, now)
    }

    fun put(result: ScanResult, now: Instant) = results.put(result.repository.fullName, result, now)
}

/** Values that stay fresh for [ttl], keyed ignoring case. */
class ExpiringCache<T : Any>(private val ttl: Duration = Duration.ofMinutes(10)) {
    private class Entry<T>(val value: T, val at: Instant)

    private val entries = HashMap<String, Entry<T>>()

    fun get(key: String, now: Instant): T? = synchronized(entries) {
        entries[key.lowercase()]?.takeIf { Duration.between(it.at, now) < ttl }?.value
    }

    fun put(key: String, value: T, now: Instant) = synchronized(entries) {
        entries[key.lowercase()] = Entry(value, now)
    }
}
