package dev.jamesrich.sofaking.backend.providers.manifest

import dev.jamesrich.sofaking.backend.providers.ProviderContext
import dev.jamesrich.sofaking.backend.runCatchingCancellable
import java.time.Duration

/** The two ways a resolve can take its URL from somewhere other than the manifest: an option, or a page. */
internal class ResolveSources(private val context: ProviderContext, private val fetcher: ManifestFetcher) {
    /** [url] as the manifest's `override` and `page` change it; the override wins. */
    suspend fun url(loaded: Loaded, r: ManifestResolve, entry: Listed, scope: TemplateScope, url: String): String {
        val base = r.override?.let { ManifestTemplate.render("\${${it.option}}", scope).trimEnd('/') }
        return when {
            r.override != null && !base.isNullOrEmpty() ->
                "$base/" + ManifestTemplate.render(r.override.suffix, scope)

            r.page != null && (r.page.requireVar == null || !entry.vars[r.page.requireVar].isNullOrEmpty()) ->
                fromPage(loaded, r, r.page, entry, scope, url)

            else -> url
        }
    }

    private suspend fun fromPage(
        loaded: Loaded,
        r: ManifestResolve,
        page: ManifestResolvePage,
        entry: Listed,
        scope: TemplateScope,
        fallback: String,
    ): String = context.cached("page:${loaded.hash}:${entry.channel.id}", Duration.ofSeconds(page.cacheSeconds)) {
        val headers = r.headers.mapValues { ManifestTemplate.render(it.value, scope) }
        val html = runCatchingCancellable {
            context.http.get(ManifestTemplate.render(page.url, scope), headers).require("page").text()
        }.getOrNull()
        html?.let { Regex(page.pattern).find(it)?.value } ?: fallback
    }
}
