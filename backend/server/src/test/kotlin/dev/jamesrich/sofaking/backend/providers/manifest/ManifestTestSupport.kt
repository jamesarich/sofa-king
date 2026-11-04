package dev.jamesrich.sofaking.backend.providers.manifest

import dev.jamesrich.sofaking.backend.providers.ProviderScope
import java.nio.file.Files

/** The shipped manifest of [service] as a client for provider [providerId], with the source file's [options]. */
internal fun ProviderScope.manifestClient(
    providerId: String,
    service: String,
    options: Map<String, String> = emptyMap(),
) = ManifestClient(
    context(providerId, options),
    ProviderManifests(Files.createTempDirectory("sofa-manifests-").resolve("none")),
    service,
)
