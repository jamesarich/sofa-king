package dev.jamesrich.sofaking.backend.providers.manifest

import dev.jamesrich.sofaking.backend.providers.ProviderContext
import dev.jamesrich.sofaking.backend.providers.array
import dev.jamesrich.sofaking.backend.providers.long
import dev.jamesrich.sofaking.backend.runCatchingCancellable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import java.time.Duration

/** The channel a template renders for. */
internal class ChannelScope(val id: String, val vars: Map<String, String>)

/** Runs a manifest's requests for one provider: templates, paging, decoding, and sharing an answer within its TTL. */
internal class ManifestFetcher(private val context: ProviderContext, private val service: String) {
    fun scope(loaded: Loaded, channel: ChannelScope? = null, page: Int? = null) = TemplateScope(
        context.options,
        loaded.manifest.defaults,
        context.clock,
        { context.deviceId() },
        page,
        channel?.id,
        channel?.vars.orEmpty(),
    )

    /** The items request [name] answers with, or null when it is optional and failed. */
    suspend fun items(loaded: Loaded, name: String, channel: ChannelScope?): List<JsonElement>? {
        val request = loaded.manifest.requests.getValue(name)
        val run: suspend () -> List<JsonElement> = { fetch(loaded, name, request, channel) }
        val call: suspend () -> List<JsonElement> = if (request.cacheSeconds > 0 && channel == null) {
            { context.cached("request:$name:${loaded.hash}", Duration.ofSeconds(request.cacheSeconds), run) }
        } else {
            run
        }
        return if (request.optional) runCatchingCancellable { call() }.getOrNull() else call()
    }

    private suspend fun fetch(
        loaded: Loaded,
        name: String,
        request: ManifestRequest,
        channel: ChannelScope?,
    ): List<JsonElement> {
        val paging = request.paging
        val items = ArrayList<JsonElement>()
        var total = 1
        var fetched = 0
        while (fetched < total && fetched < (paging?.maxPages ?: 1)) {
            val document = send(loaded, name, request, scope(loaded, channel, paging?.let { it.start + fetched }))
            items += (if (document is JsonArray) document else document.at(request.items)).array
            fetched++
            if (fetched == 1 && paging?.count != null && paging.size != null) {
                val size = document.at(paging.size).long ?: paging.defaultSize
                val count = document.at(paging.count).long ?: 0
                if (size > 0 && count > size) total = ((count + size - 1) / size).toInt()
            }
        }
        return items
    }

    private suspend fun send(
        loaded: Loaded,
        name: String,
        request: ManifestRequest,
        scope: TemplateScope,
    ): JsonElement {
        val headers = (loaded.manifest.headers + request.headers).mapValues { ManifestTemplate.render(it.value, scope) }
        val url = ManifestTemplate.render(request.url, scope)
        val fetched = if (request.method == "POST") {
            val body = ManifestTemplate.render(request.body.orEmpty(), scope)
            context.http.post(url, headers, body, request.contentType ?: "application/json")
        } else {
            context.http.get(url, headers)
        }.require("$service $name")
        if (request.decode.isEmpty()) return fetched.json()
        return decodeBody(fetched.body, request.decode, "$service $name")
    }
}
