package dev.jamesrich.sofaking

/** The entries of one copy object, each keyed by the resource name it copies; see [TvCopy]. */
internal class CopyTable {
    private val entries = LinkedHashMap<String, String>()

    val twins: Map<String, String> get() = entries

    fun twin(key: String, text: String): String {
        check(entries.put(key, text) == null) { "$key is in the copy table twice" }
        return text
    }

    /** [template] with each `%1$s` or `%2$d` replaced by that argument, as Compose's resources number them. */
    fun fill(template: String, vararg arguments: Any): String =
        ARGUMENT.replace(template) { arguments[it.groupValues[1].toInt() - 1].toString() }

    private companion object {
        val ARGUMENT = Regex("%(\\d)\\$[sd]")
    }
}
