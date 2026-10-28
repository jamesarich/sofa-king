package dev.jamesrich.sofaking.visualizer

/**
 * One MilkDrop-style preset: the starting values of the variables the engine reads each frame, and the three equation
 * programs (run once, every frame, and at every vertex of the warp mesh). The text format is MilkDrop's `.milk`.
 */
class MilkPreset(
    val name: String,
    val values: Map<String, Double>,
    val init: String,
    val frame: String,
    val pixel: String,
    val waves: List<CustomDef> = emptyList(),
    val shapes: List<CustomDef> = emptyList(),
) {
    /** The preset as the text [parse] reads back: only what the engine uses, equations on one line each. */
    fun toText(): String = buildString {
        for ((key, value) in values) if (Defaults[key] != value) appendLine("$key=$value")
        programLine("per_frame_init_1", init)
        programLine("per_frame_1", frame)
        programLine("per_pixel_1", pixel)
        waves.forEachIndexed { i, wave -> appendCustom("wave", i, wave, WAVE_DEFAULTS) }
        shapes.forEachIndexed { i, shape -> appendCustom("shape", i, shape, SHAPE_DEFAULTS) }
    }

    private fun StringBuilder.programLine(key: String, program: String) {
        if (program.isNotBlank()) appendLine("$key=$program")
    }

    private fun StringBuilder.appendCustom(kind: String, index: Int, def: CustomDef, defaults: Map<String, Double>) {
        for ((key, value) in def.values) {
            if (key == "enabled" ||
                defaults[key] != value
            ) {
                appendLine("${kind}code_${index}_$key=$value")
            }
        }
        programLine("${kind}_${index}_init1", def.init)
        programLine("${kind}_${index}_per_frame1", def.frame)
        programLine("${kind}_${index}_per_point1", def.point)
    }

    companion object {
        /** What a preset that sets nothing gets, by variable name. */
        val Defaults: Map<String, Double> = mapOf(
            "decay" to 0.98,
            "gamma" to 1.0,
            "echo_zoom" to 1.0,
            "echo_alpha" to 0.0,
            "echo_orient" to 0.0,
            "wave_mode" to 0.0,
            "additivewave" to 0.0,
            "wavedots" to 0.0,
            "wavethick" to 0.0,
            "modwavealphabyvolume" to 0.0,
            "maximizewavecolor" to 1.0,
            "wave_alpha" to 0.8,
            "wave_scale" to 1.0,
            "wave_smoothing" to 0.75,
            "wave_r" to 1.0,
            "wave_g" to 1.0,
            "wave_b" to 1.0,
            "wave_x" to 0.5,
            "wave_y" to 0.5,
            "ob_size" to 0.01,
            "ob_r" to 0.0,
            "ob_g" to 0.0,
            "ob_b" to 0.0,
            "ob_a" to 0.0,
            "ib_size" to 0.01,
            "ib_r" to 0.25,
            "ib_g" to 0.25,
            "ib_b" to 0.25,
            "ib_a" to 0.0,
            "zoom" to 1.0,
            "zoomexp" to 1.0,
            "rot" to 0.0,
            "cx" to 0.5,
            "cy" to 0.5,
            "dx" to 0.0,
            "dy" to 0.0,
            "warp" to 1.0,
            "sx" to 1.0,
            "sy" to 1.0,
            "brighten" to 0.0,
            "darken" to 0.0,
            "solarize" to 0.0,
            "invert" to 0.0,
        )

        // The names a `.milk` file gives a value, when they are not the name the equations use.
        private val FileKeys = mapOf(
            "fdecay" to "decay",
            "fgammaadj" to "gamma",
            "fvideoechozoom" to "echo_zoom",
            "fvideoechoalpha" to "echo_alpha",
            "nvideoechoorientation" to "echo_orient",
            "nwavemode" to "wave_mode",
            "badditivewaves" to "additivewave",
            "bwavedots" to "wavedots",
            "bwavethick" to "wavethick",
            "bmodwavealphabyvolume" to "modwavealphabyvolume",
            "bmaximizewavecolor" to "maximizewavecolor",
            "fwavealpha" to "wave_alpha",
            "fwavescale" to "wave_scale",
            "fwavesmoothing" to "wave_smoothing",
            "bbrighten" to "brighten",
            "bdarken" to "darken",
            "bsolarize" to "solarize",
            "binvert" to "invert",
        )

        /** Reads a pack: presets as [toText] writes them, each after a line `@@ name`. */
        fun parsePack(text: String): List<MilkPreset> {
            val presets = ArrayList<MilkPreset>()
            var name: String? = null
            val body = StringBuilder()
            fun flush() {
                name?.let { presets += parse(it, body.toString()) }
                body.clear()
            }
            for (line in text.lineSequence()) {
                if (line.startsWith(PACK_MARK)) {
                    flush()
                    name = line.removePrefix(PACK_MARK).trim()
                } else {
                    body.appendLine(line)
                }
            }
            flush()
            return presets
        }

        /** The line that starts a preset in a pack. */
        const val PACK_MARK = "@@ "

        /**
         * Reads a `.milk` file. Lines the engine has no use for (shaders, custom waves and shapes, motion vectors) are
         * skipped, so a preset that leans on them draws without them rather than not at all.
         */
        fun parse(name: String, text: String): MilkPreset {
            val values = HashMap<String, Double>()
            val programs = mapOf(INIT to ArrayList<Pair<Int, String>>(), FRAME to ArrayList(), PIXEL to ArrayList())
            val customs = CustomCollector()
            for (raw in text.lineSequence()) {
                val at = raw.indexOf('=')
                val key = raw.substring(0, maxOf(at, 0)).trim().lowercase()
                val value = raw.substring(at + 1).trim()
                val line = programOf(key)
                when {
                    at <= 0 || customs.take(key, value) -> Unit
                    line != null -> programs.getValue(line.first).add(line.second to value)
                    else -> value.toDoubleOrNull()?.let { values[FileKeys[key] ?: key] = it }
                }
            }
            return MilkPreset(
                name,
                values,
                join(programs.getValue(INIT)),
                join(programs.getValue(FRAME)),
                join(programs.getValue(PIXEL)),
                customs.waves(),
                customs.shapes(),
            )
        }

        private const val INIT = "per_frame_init_"
        private const val FRAME = "per_frame_"
        private const val PIXEL = "per_pixel_"

        /** The program and line number a key like `per_frame_12` names, or null for any other key. */
        private fun programOf(key: String): Pair<String, Int>? {
            val prefix = listOf(INIT, FRAME, PIXEL).firstOrNull { key.startsWith(it) } ?: return null
            return key.removePrefix(prefix).toIntOrNull()?.let { prefix to it }
        }

        /** A program's lines in order, joined as they are: MilkDrop lets a line end mid-statement. */
        internal fun join(lines: List<Pair<Int, String>>): String = lines.sortedBy {
            it.first
        }.map { it.second.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
    }
}
