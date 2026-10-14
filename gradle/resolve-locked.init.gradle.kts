// Used by update-locks.sh. Adds resolveLockedConfigurations to every project: it resolves the graph of each configuration
// the project's gradle.lockfile names, so --write-locks rewrites all of them, including the Apple, web and test
// configurations no single host's build tasks resolve.
allprojects {
    val lockfile = file("gradle.lockfile")
    val projectConfigurations = configurations
    tasks.register("resolveLockedConfigurations") {
        doLast {
            if (!lockfile.exists()) return@doLast
            lockfile.readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .flatMap { it.substringAfter('=').split(',') }
                .filter { it.isNotEmpty() }
                .toSortedSet()
                .forEach { name ->
                    projectConfigurations.findByName(name)?.takeIf { it.isCanBeResolved }?.incoming?.resolutionResult?.root
                }
        }
    }
}
