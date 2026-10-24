package dev.jamesrich.sofaking.backend.setup

/** One option a service takes from the source file, as the portal asks for it. [pattern] is what a value must match. */
class ServiceOption(val key: String, val label: String, val hint: String, val required: Boolean = false, val pattern: Regex? = null)

/** One native service as the portal presents it: what it is called, what it carries, and the options it takes. */
class ServiceInfo(val service: String, val label: String, val carries: String, val options: List<ServiceOption> = emptyList(), startsOn: Boolean = true) {
    /** Whether a fresh server ticks the service before anything is set: it needs no option, and lists channels without one. */
    val startsOn: Boolean = startsOn && options.none { it.required }
}

/**
 * The native services the server has a client for, in the order the starter policy takes them: the ones whose
 * streams hold up best first. Every service here is in `NativeServices.names`, and a test keeps the two in step.
 */
object ServiceCatalog {
    private val zip = ServiceOption("zip", "ZIP code", "Five digits; picks the PBS member stations for the area, and without it the service lists nothing", pattern = Regex("\\d{5}"))
    private val dma = ServiceOption("dma", "DMA code", "The Nielsen market number for your local news, such as 679 for Des Moines", pattern = Regex("\\d{1,4}"))
    private val market = ServiceOption("market", "Market", "The market name as Local Now writes it, such as des-moines", pattern = Regex("[a-z0-9-]{1,64}"))
    private val region = ServiceOption("region", "Region", "Two-letter country code of the Samsung TV Plus catalog; us when empty", pattern = Regex("[a-z]{2}"))
    private val url = ServiceOption("url", "Tuner address", "The HDHomeRun's address on the LAN, such as http://192.168.1.50", required = true, pattern = Regex("https?://[^\\s/]+"))

    val all: List<ServiceInfo> = listOf(
        ServiceInfo("direct", "Broadcasters' own feeds", "Live news and public channels from CBS News, ARD, RTVE, Euronews and other broadcasters' own sites"),
        ServiceInfo("whale", "Whale TV+", "About 200 channels across every genre"),
        ServiceInfo("samsung", "Samsung TV Plus", "Samsung's free channels, by country", listOf(region)),
        ServiceInfo("vizio", "WatchFree+", "Vizio's free channels"),
        ServiceInfo("lg", "LG Channels", "LG's free channels"),
        ServiceInfo("pluto", "Pluto TV", "Hundreds of channels with a full guide"),
        ServiceInfo("plex", "Plex free live TV", "Plex's free channels"),
        ServiceInfo("roku", "The Roku Channel", "Roku's free channels, without the DRM ones"),
        ServiceInfo("xumo", "Xumo Play", "Xumo's free channels"),
        ServiceInfo("tubi", "Tubi", "Tubi's live channels"),
        ServiceInfo("tcl", "TCL TV+", "TCL's free channels"),
        ServiceInfo("stirr", "STIRR", "Sinclair's local and national channels"),
        ServiceInfo("freelivesports", "FreeLiveSports.tv", "Free sports channels"),
        ServiceInfo("localnow", "Local Now", "Local news for one market, and national channels", listOf(dma, market)),
        ServiceInfo("pbs", "PBS member stations", "Your local PBS station and its Create and World channels", listOf(zip), startsOn = false),
        ServiceInfo("hallmark", "Hallmark+", "Four Hallmark channels"),
        ServiceInfo("ballysports", "Bally Sports", "Bally Sports' free channels"),
        ServiceInfo("adultswim", "Adult Swim marathons", "Adult Swim's marathon streams"),
        ServiceInfo("hdhomerun", "HDHomeRun tuner", "Your antenna's broadcast stations, from an HDHomeRun on the LAN", listOf(url)),
    )

    fun byService(service: String): ServiceInfo? = all.firstOrNull { it.service == service }

    /** The services in starter-policy priority order. */
    val order: List<String> get() = all.map { it.service }
}
