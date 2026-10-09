package dev.jamesrich.sofaking.data

object M3uParser {
    private val attribute = Regex("""([\w-]+)="([^"]*)"""")

    fun parse(text: String): List<Channel> {
        val channels = mutableListOf<Channel>()
        var pending: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF") -> pending = line
                line.isEmpty() || line.startsWith("#") -> Unit
                pending != null -> {
                    toChannel(pending, line)?.let(channels::add)
                    pending = null
                }
            }
        }
        return channels.sortedBy { it.number }
    }

    private fun toChannel(info: String, url: String): Channel? {
        val attrs = attribute.findAll(info).associate { it.groupValues[1] to it.groupValues[2] }
        val title = info.substringAfterLast(',').trim()
        val number = attrs["tvg-chno"]?.toDoubleOrNull() ?: return null
        return Channel(
            guideId = attrs["tvg-id"]?.takeIf { it.isNotEmpty() } ?: attrs["tvg-chno"].orEmpty(),
            number = number,
            name = attrs["tvg-name"]?.takeIf { it.isNotEmpty() } ?: title,
            logo = attrs["tvg-logo"]?.takeIf { it.isNotEmpty() },
            group = attrs["group-title"]?.takeIf { it.isNotEmpty() } ?: "Other",
            streamUrl = url,
        )
    }
}
