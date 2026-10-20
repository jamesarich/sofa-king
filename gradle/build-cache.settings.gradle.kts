// The remote cache on omv, used only when credentials are set: sofaking.cache.* Gradle properties or SOFAKING_CACHE_*
// variables. CI pushes from main; every other build only reads. Each top-level build applies this, since in a composite
// only the outermost build's cache settings count and CI builds app/, backend/ and contract/ on their own; build-logic,
// only ever included, does not.
fun cacheSetting(name: String): String? =
    providers.gradleProperty("sofaking.cache.$name")
        .orElse(providers.environmentVariable("SOFAKING_CACHE_${name.uppercase()}"))
        .orNull?.trim()?.takeIf { it.isNotEmpty() }

val cacheUsername = cacheSetting("username")
val cachePassword = cacheSetting("password")
buildCache {
    remote<HttpBuildCache> {
        url = uri(cacheSetting("url") ?: "https://gradle-cache.hiddentemple.xyz/cache/")
        isEnabled = cacheUsername != null && cachePassword != null
        isPush = cacheSetting("push") == "true"
        credentials {
            username = cacheUsername
            password = cachePassword
        }
    }
}
