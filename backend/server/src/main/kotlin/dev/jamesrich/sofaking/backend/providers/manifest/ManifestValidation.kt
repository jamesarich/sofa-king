package dev.jamesrich.sofaking.backend.providers.manifest

private val CREDENTIAL_HEADERS = setOf("authorization", "cookie", "proxy-authorization")
private val DECODE_STEPS = setOf("base64", "zlib", "gzip", "nextData")
private val JOIN_FIELDS = setOf("logo", "name", "group", "number")
private val SERVICE_NAME = Regex("[a-z][a-z0-9-]*")

/** What is wrong with the manifest; empty when it can be used. */
fun ProviderManifest.validate(): List<String> = ManifestChecks(this).run()

private fun filterErrors(where: String, filter: ManifestFilter): List<String> = buildList {
    if (filter.op !in FILTER_OPS) add("$where: unknown op ${filter.op}")
    if (filter.op in setOf("eq", "neq", "startsWith") && filter.value == null) add("$where: ${filter.op} needs a value")
    if (filter.anyOf != null && (filter.path.isNotEmpty() || filter.op in setOf("truthy", "falsy"))) {
        add("$where: anyOf replaces path and reads text, not truthy or falsy")
    }
}

private fun guideKeyErrors(g: ManifestGuide, requests: Map<String, ManifestRequest>, channelVars: Set<String>) =
    buildList {
        val request = g.request?.let { requests[it] }
        if (request == null) {
            add("guide: ${g.mode} needs a request that exists")
        } else if (request.optional) {
            add("guide.request: cannot be optional")
        }
        if (g.key == null) add("guide: ${g.mode} needs key")
        if (g.pastHours != null && g.pastHours < 0) add("guide.pastHours must not be negative")
        if (g.mode == "grouped" && g.programs == null) add("guide: grouped needs programs")
        if (g.mode == "keyed" && g.programs != null) add("guide: keyed takes no programs")
        g.keyVar?.let { if (it !in channelVars) add("guide.keyVar: no channel variable $it") }
    }

private fun resolveSourceErrors(r: ManifestResolve, optionKeys: Set<String>, channelVars: Set<String>) = buildList {
    r.override?.let {
        if (it.option !in
            optionKeys
        ) {
            add("resolve.override: ${it.option} is not an option or a default")
        }
    }
    r.page?.let { page ->
        if (runCatching { Regex(page.pattern) }.isFailure) add("resolve.page: bad pattern")
        if (page.cacheSeconds < 0) add("resolve.page: cacheSeconds must not be negative")
        page.requireVar?.let { if (it !in channelVars) add("resolve.page.requireVar: no channel variable $it") }
    }
}

private class ManifestChecks(private val m: ProviderManifest) {
    private val errors = ArrayList<String>()
    private val optionKeys = m.defaults.keys + m.catalog?.options?.map { it.key }.orEmpty()
    private val credentialOptions = m.catalog?.options?.map { it.key }.orEmpty().toSet()
    private val channelVars: Set<String> =
        m.channels.vars.keys + m.channels.join?.vars?.keys.orEmpty()

    fun run(): List<String> {
        if (!SERVICE_NAME.matches(m.service)) errors += "service must be lowercase letters, digits and hyphens"
        if (m.version != 1) errors += "version ${m.version} is not supported"
        if (m.protocol !in setOf("hls", "mpegts")) errors += "protocol must be hls or mpegts"
        if (m.cacheSeconds < 0) errors += "cacheSeconds must not be negative"
        m.catalog?.options?.forEach { option ->
            option.pattern?.let {
                runCatching { Regex(it) }.onFailure { errors += "option ${option.key}: bad pattern" }
            }
        }
        headers("headers", m.headers, null)
        val guideRequest = m.guide?.let { g -> g.request.takeIf { g.mode == "perChannel" } }
        m.requests.forEach { (name, request) ->
            request(name, request, channelVars.takeIf { name == guideRequest })
        }
        channels()
        m.guide?.let(::guide)
        resolve()
        return errors
    }

    private fun template(
        where: String,
        text: String,
        channel: Set<String>?,
        page: Boolean = false,
        cursor: Boolean = false,
    ) {
        val names = TemplateNames(optionKeys, channel, page, cursor)
        ManifestTemplate.references(text).forEach { name ->
            ManifestTemplate.problem(name, names)?.let { errors += "$where: $it" }
        }
    }

    private fun headers(
        where: String,
        headers: Map<String, String>,
        channel: Set<String>?,
        page: Boolean = false,
        cursor: Boolean = false,
    ) {
        headers.forEach { (key, value) ->
            if (key.lowercase() in CREDENTIAL_HEADERS) {
                val reference = ManifestTemplate.references(value).singleOrNull()
                if (reference == null || value != "\${$reference}" || reference !in credentialOptions) {
                    errors += "$where: $key must be a \${option} the household sets, never a literal value"
                }
            }
            template("$where.$key", value, channel, page, cursor)
        }
    }

    private fun request(name: String, r: ManifestRequest, channel: Set<String>?) {
        val where = "requests.$name"
        val paged = r.paging != null
        val cursor = r.paging?.cursorPath != null
        if (r.method !in setOf("GET", "POST")) errors += "$where: method ${r.method} is not GET or POST"
        if (r.method == "GET" && r.body != null) errors += "$where: a GET has no body"
        if (r.cacheSeconds < 0) errors += "$where: cacheSeconds must not be negative"
        if (r.cacheSeconds > 0 && channel != null) errors += "$where: a per-channel request cannot be cached"
        template("$where.url", r.url, channel, paged, cursor)
        r.body?.let { template("$where.body", it, channel, paged, cursor) }
        headers("$where.headers", r.headers, channel, paged, cursor)
        if (cursor && r.cacheSeconds > 0) errors += "$where: a request paged by cursorPath cannot be cached"
        r.decode.filterNot(::knownStep).forEach { errors += "$where: unknown decode step $it" }
        r.paging?.let { paging ->
            if (paging.maxPages < 1 || paging.start < 0) errors += "$where: paging needs maxPages of at least 1"
        }
    }

    private fun knownStep(step: String) =
        step in DECODE_STEPS || (step.startsWith(JSON_ARRAY_AFTER) && step.length > JSON_ARRAY_AFTER.length)

    private fun channels() {
        val c = m.channels
        if ((c.from == null) == (c.static == null)) errors += "channels: exactly one of from and static"
        c.from?.let { from ->
            val request = m.requests[from]
            if (request == null) {
                errors += "channels.from: no request named $from"
            } else if (request.optional) {
                errors += "channels.from: the channel request cannot be optional"
            }
        }
        channelMap(c)
        c.join?.let(::join)
    }

    private fun channelMap(c: ManifestChannels) {
        c.filter.forEach { errors += filterErrors("channels.filter", it) }
        c.map.tagsWhen.forEach { errors += filterErrors("channels.map.tagsWhen", it.condition) }
        field("channels.map.id", c.map.id)
        field("channels.map.name", c.map.name)
        c.map.tagsFrom?.let { field("channels.map.tagsFrom", it) }
        listOf("logo" to c.map.logo, "group" to c.map.group, "number" to c.map.number).forEach { (key, value) ->
            value?.let { field("channels.map.$key", it) }
        }
        c.vars.forEach { (key, value) -> field("channels.vars.$key", value) }
        if (c.distinctBy != null && c.distinctBy != "id") errors += "channels.distinctBy must be id"
    }

    private fun join(join: ManifestJoin) {
        if (m.requests[join.request] == null) errors += "channels.join: no request named ${join.request}"
        template("channels.join.on", join.on, m.channels.vars.keys)
        join.vars.forEach { (key, value) -> field("channels.join.vars.$key", value) }
        join.set.forEach { (key, value) ->
            if (key !in JOIN_FIELDS) errors += "channels.join.set: unknown field $key"
            field("channels.join.set.$key", value)
        }
    }

    private fun guide(g: ManifestGuide) {
        if (!m.hasGuide) errors += "guide: hasGuide is false"
        when (g.mode) {
            "inline" -> {
                if (g.programs == null) errors += "guide: inline needs programs"
                if (g.request != null) errors += "guide: inline takes no request"
                if (m.channels.static != null) errors += "guide: inline needs a channel request"
            }

            "perChannel" -> {
                if (g.request == null) {
                    errors += "guide: perChannel needs a request"
                } else if (m.requests[g.request] == null) {
                    errors += "guide.request: no request named ${g.request}"
                } else if (m.requests[g.request]?.optional == true) {
                    errors += "guide.request: cannot be optional"
                }
            }

            "keyed", "grouped" -> errors += guideKeyErrors(g, m.requests, channelVars)

            else -> errors += "guide: mode ${g.mode} is not inline, perChannel, keyed or grouped"
        }
        g.requireVar?.let { if (it !in channelVars) errors += "guide.requireVar: no channel variable $it" }
        val p = g.map
        field("guide.map.title", p.title)
        field("guide.map.start", p.start)
        field("guide.map.end", p.end)
        listOf(p.description, p.subTitle, p.icon, p.category, p.rating, p.season, p.episode).forEach {
            it?.let { f -> field("guide.map", f) }
        }
    }

    private fun resolve() {
        val r = m.resolve
        template("resolve.url", r.url, channelVars)
        r.format.forEach { template("resolve.format", it, channelVars) }
        r.macros.forEach { (key, value) -> template("resolve.macros.$key", value, channelVars) }
        headers("resolve.headers", r.headers, channelVars)
        errors += resolveSourceErrors(r, optionKeys, channelVars)
        r.override?.let { template("resolve.override.suffix", it.suffix, channelVars) }
        r.page?.let { template("resolve.page.url", it.url, channelVars) }
        r.post?.let { post ->
            template("resolve.post.body", post.body, channelVars)
            if (post.urlPath.isEmpty()) errors += "resolve.post: urlPath is required"
        }
        if (r.expiresSeconds != null && r.expiresSeconds <= 0) errors += "resolve.expiresSeconds must be positive"
    }

    private fun field(where: String, field: Field) {
        val s = field.spec
        if (listOfNotNull(s.path, s.firstOf, s.firstHttp, s.value).size != 1) {
            errors += "$where: exactly one of path, firstOf, firstHttp and value"
        }
        if (s.asType != null && s.asType !in FIELD_TYPES) errors += "$where: unknown as ${s.asType}"
        if (s.asType != null && s.usRating) errors += "$where: as and usRating cannot combine"
    }
}
