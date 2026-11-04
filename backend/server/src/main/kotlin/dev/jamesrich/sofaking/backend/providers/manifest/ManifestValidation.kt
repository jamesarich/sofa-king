package dev.jamesrich.sofaking.backend.providers.manifest

private val CREDENTIAL_HEADERS = setOf("authorization", "cookie", "proxy-authorization")
private val DECODE_STEPS = setOf("base64", "zlib", "gzip", "nextData")
private val JOIN_FIELDS = setOf("logo", "name", "group", "number")
private val SERVICE_NAME = Regex("[a-z][a-z0-9-]*")

/** What is wrong with the manifest; empty when it can be used. */
fun ProviderManifest.validate(): List<String> = ManifestChecks(this).run()

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
        val guideRequest = m.guide?.request
        m.requests.forEach { (name, request) ->
            request(name, request, channelVars.takeIf { name == guideRequest })
        }
        channels()
        m.guide?.let(::guide)
        resolve()
        return errors
    }

    private fun template(where: String, text: String, channel: Set<String>?, page: Boolean = false) {
        val names = TemplateNames(optionKeys, channel, page)
        ManifestTemplate.references(text).forEach { name ->
            ManifestTemplate.problem(name, names)?.let { errors += "$where: $it" }
        }
    }

    private fun headers(where: String, headers: Map<String, String>, channel: Set<String>?, page: Boolean = false) {
        headers.forEach { (key, value) ->
            if (key.lowercase() in CREDENTIAL_HEADERS) {
                val reference = ManifestTemplate.references(value).singleOrNull()
                if (reference == null || value != "\${$reference}" || reference !in credentialOptions) {
                    errors += "$where: $key must be a \${option} the household sets, never a literal value"
                }
            }
            template("$where.$key", value, channel, page)
        }
    }

    private fun request(name: String, r: ManifestRequest, channel: Set<String>?) {
        val where = "requests.$name"
        val paged = r.paging != null
        if (r.method !in setOf("GET", "POST")) errors += "$where: method ${r.method} is not GET or POST"
        if (r.method == "GET" && r.body != null) errors += "$where: a GET has no body"
        if (r.cacheSeconds < 0) errors += "$where: cacheSeconds must not be negative"
        if (r.cacheSeconds > 0 && channel != null) errors += "$where: a per-channel request cannot be cached"
        template("$where.url", r.url, channel, paged)
        r.body?.let { template("$where.body", it, channel, paged) }
        headers("$where.headers", r.headers, channel, paged)
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
        c.filter.forEach { filter ->
            if (filter.op !in FILTER_OPS) errors += "channels.filter: unknown op ${filter.op}"
            if (filter.op in setOf("eq", "neq") && filter.value == null) {
                errors += "channels.filter: ${filter.op} needs a value"
            }
        }
        field("channels.map.id", c.map.id)
        field("channels.map.name", c.map.name)
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

            else -> errors += "guide: mode ${g.mode} is not inline or perChannel"
        }
        g.requireVar?.let { if (it !in channelVars) errors += "guide.requireVar: no channel variable $it" }
        val p = g.map
        field("guide.map.title", p.title)
        field("guide.map.start", p.start)
        field("guide.map.end", p.end)
        listOf(p.description, p.subTitle, p.icon, p.category, p.rating).forEach {
            it?.let { f -> field("guide.map", f) }
        }
    }

    private fun resolve() {
        val r = m.resolve
        template("resolve.url", r.url, channelVars)
        r.format.forEach { template("resolve.format", it, channelVars) }
        r.macros.forEach { (key, value) -> template("resolve.macros.$key", value, channelVars) }
        headers("resolve.headers", r.headers, channelVars)
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
