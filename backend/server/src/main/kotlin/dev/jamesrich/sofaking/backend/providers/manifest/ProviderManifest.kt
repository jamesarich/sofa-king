package dev.jamesrich.sofaking.backend.providers.manifest

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** One service as data: its requests and how the answers map onto channels, programs and a stream. */
@Serializable
data class ProviderManifest(
    val service: String,
    val version: Int = 1,
    val hasGuide: Boolean = false,
    val protocol: String = "hls",
    val catalog: ManifestCatalog? = null,
    val defaults: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val cacheSeconds: Long = 0,
    val requests: Map<String, ManifestRequest> = emptyMap(),
    val channels: ManifestChannels,
    val guide: ManifestGuide? = null,
    val resolve: ManifestResolve,
)

@Serializable
data class ManifestCatalog(
    val label: String,
    val carries: String,
    val priority: Int = 0,
    val startsOn: Boolean = true,
    val options: List<ManifestOption> = emptyList(),
)

@Serializable
data class ManifestOption(
    val key: String,
    val label: String,
    val hint: String,
    val required: Boolean = false,
    val pattern: String? = null,
)

@Serializable
data class ManifestRequest(
    val method: String = "GET",
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val contentType: String? = null,
    val paging: ManifestPaging? = null,
    val decode: List<String> = emptyList(),
    val items: String = "",
    val optional: Boolean = false,
    val cacheSeconds: Long = 0,
)

/** Pages `${page}` from [start] while the first answer's [count] exceeds [size], up to [maxPages] pages. */
@Serializable
data class ManifestPaging(
    val start: Int = 1,
    val maxPages: Int = 1,
    val count: String? = null,
    val size: String? = null,
    val defaultSize: Long = 0,
    val cursorPath: String? = null,
)

@Serializable
data class ManifestChannels(
    val from: String? = null,
    val static: List<JsonElement>? = null,
    val filter: List<ManifestFilter> = emptyList(),
    val map: ManifestChannelMap,
    val vars: Map<String, Field> = emptyMap(),
    val join: ManifestJoin? = null,
    val distinctBy: String? = null,
    val sortBy: String? = null,
    val requireNonEmpty: Boolean = false,
)

@Serializable
data class ManifestFilter(
    val path: String = "",
    val op: String,
    val value: String? = null,
    val default: String? = null,
    val anyOf: List<String>? = null,
)

/** Adds [tag] to a channel whose item passes [condition]. */
@Serializable
data class ManifestTagWhen(val tag: String, @SerialName("when") val condition: ManifestFilter)

@Serializable
data class ManifestChannelMap(
    val id: Field,
    val name: Field,
    val logo: Field? = null,
    val group: Field? = null,
    val tags: List<String> = emptyList(),
    val tagsWhen: List<ManifestTagWhen> = emptyList(),
    val number: Field? = null,
)

/** Adds a request's items to each channel whose [on] template equals the item's [key]: more [vars] and [set] fields. */
@Serializable
data class ManifestJoin(
    val request: String,
    val key: String,
    val on: String,
    val vars: Map<String, Field> = emptyMap(),
    val set: Map<String, Field> = emptyMap(),
)

@Serializable
data class ManifestGuide(
    val mode: String,
    val programs: String? = null,
    val request: String? = null,
    val requireVar: String? = null,
    val key: String? = null,
    val keyVar: String? = null,
    val map: ManifestProgramMap,
)

@Serializable
data class ManifestProgramMap(
    val title: Field,
    val start: Field,
    val end: Field,
    val description: Field? = null,
    val subTitle: Field? = null,
    val icon: Field? = null,
    val category: Field? = null,
    val rating: Field? = null,
    val season: Field? = null,
    val episode: Field? = null,
)

@Serializable
data class ManifestResolve(
    val url: String,
    val format: List<String> = emptyList(),
    val macros: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val expiresSeconds: Long? = null,
    val missing: String = "Channel is no longer listed",
)

/** How one value is read out of a JSON item; a bare string in a manifest is `{"path": that string}`. */
@Serializable(with = FieldSerializer::class)
data class Field(val spec: FieldSpec)

@Serializable
data class FieldSpec(
    val path: String? = null,
    val firstOf: List<String>? = null,
    val firstHttp: List<String>? = null,
    val value: String? = null,
    val fallback: String? = null,
    val before: String? = null,
    val trim: Boolean = false,
    @SerialName("as") val asType: String? = null,
    val http: Boolean = false,
    val usRating: Boolean = false,
    val sentenceCase: Boolean = false,
    val dropIf: String? = null,
    val unlessEquals: String? = null,
)

internal object FieldSerializer : KSerializer<Field> {
    override val descriptor: SerialDescriptor = FieldSpec.serializer().descriptor

    override fun deserialize(decoder: Decoder): Field {
        val json = decoder as JsonDecoder
        val element = json.decodeJsonElement()
        return if (element is JsonPrimitive && element.isString) {
            Field(FieldSpec(path = element.content))
        } else {
            Field(json.json.decodeFromJsonElement(FieldSpec.serializer(), element))
        }
    }

    override fun serialize(encoder: Encoder, value: Field) =
        encoder.encodeSerializableValue(FieldSpec.serializer(), value.spec)
}
