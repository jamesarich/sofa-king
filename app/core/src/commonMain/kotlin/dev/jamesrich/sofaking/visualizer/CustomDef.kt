package dev.jamesrich.sofaking.visualizer

/**
 * One custom wave or shape of a preset: its starting values by name (`r`, `samples`, `sides`...), and its init,
 * per-frame and (for a wave) per-point programs.
 */
class CustomDef(val values: Map<String, Double>, val init: String, val frame: String, val point: String)

/** Gathers the `wavecode_N_*`, `wave_N_*`, `shapecode_N_*` and `shape_N_*` lines of a `.milk` file as they come. */
internal class CustomCollector {
    private class Parts {
        val values = HashMap<String, Double>()
        val init = ArrayList<Pair<Int, String>>()
        val frame = ArrayList<Pair<Int, String>>()
        val point = ArrayList<Pair<Int, String>>()

        fun build() = CustomDef(
            values,
            MilkPreset.join(init),
            MilkPreset.join(frame),
            MilkPreset.join(point),
        )
    }

    private val waveParts = HashMap<Int, Parts>()
    private val shapeParts = HashMap<Int, Parts>()

    /** Takes the line if it belongs to a custom wave or shape; false for any other. */
    fun take(key: String, value: String): Boolean {
        val match = KEY.matchEntire(key) ?: return false
        val (kind, index, name) = match.destructured
        val rest = FLAGS[name] ?: name
        val parts = (if (kind == "wave") waveParts else shapeParts).getOrPut(index.toInt()) { Parts() }
        if (PROGRAM.matches(rest)) {
            program(parts, rest, value)
        } else {
            value.toDoubleOrNull()?.let {
                parts.values[rest] =
                    it
            }
        }
        return true
    }

    private fun program(parts: Parts, key: String, value: String) {
        val (kind, number) = PROGRAM.matchEntire(key)!!.destructured
        val list = when (kind) {
            "init" -> parts.init
            "per_frame" -> parts.frame
            else -> parts.point
        }
        list.add(number.toInt() to value)
    }

    fun waves(): List<CustomDef> = waveParts.entries.sortedBy { it.key }.map { it.value.build() }.filter {
        it.values["enabled"] ==
            1.0
    }

    fun shapes(): List<CustomDef> = shapeParts.entries.sortedBy { it.key }.map { it.value.build() }.filter {
        it.values["enabled"] ==
            1.0
    }

    private companion object {
        // `wavecode_0_enabled`, `wave_0_per_point3`, `shapecode_2_sides`, `shape_1_init1`
        val KEY = Regex("(wave|shape)(?:code)?_(\\d+)_(.+)")
        val PROGRAM = Regex("(init|per_frame|per_point)(\\d+)")

        // The file spells a flag with a leading b; the equations do not.
        val FLAGS = mapOf(
            "bspectrum" to "spectrum",
            "busedots" to "usedots",
            "bdrawthick" to "drawthick",
            "badditive" to "additive",
        )
    }
}
