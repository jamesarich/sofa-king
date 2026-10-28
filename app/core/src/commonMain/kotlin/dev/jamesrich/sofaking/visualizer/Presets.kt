package dev.jamesrich.sofaking.visualizer

/**
 * The presets that come with the app. They are written for Sofa King, in MilkDrop's `.milk` text format, so they read
 * like any preset a household loads from a folder. None is copied from a MilkDrop pack.
 */
object Presets {
    val all: List<MilkPreset> by lazy {
        SOURCES.map { (name, text) -> MilkPreset.parse(name, text.trimIndent()) }
    }

    private val SOURCES = listOf(
        "Slow Tunnel" to """
            fDecay=0.965
            nWaveMode=0
            fWaveScale=1.4
            bAdditiveWaves=1
            per_frame_1=zoom = 1.012 + 0.03 * max(bass - 1, 0);
            per_frame_2=rot = 0.012 * sin(time * 0.4);
            per_frame_3=wave_r = 0.6 + 0.4 * sin(time * 1.1);
            per_frame_4=wave_g = 0.6 + 0.4 * sin(time * 1.3 + 2);
            per_frame_5=wave_b = 0.6 + 0.4 * sin(time * 1.7 + 4);
        """,
        "Spiral Bloom" to """
            fDecay=0.95
            nWaveMode=1
            bAdditiveWaves=1
            bWaveThick=1
            fWaveScale=1.2
            warp=0.4
            per_frame_1=wave_r = 0.5 + 0.5 * sin(time * 0.7);
            per_frame_2=wave_g = 0.4 + 0.4 * sin(time * 0.9 + 1);
            per_frame_3=wave_b = 0.8;
            per_frame_4=q1 = 0.04 * (1 + 0.5 * treb_att);
            per_pixel_1=rot = q1 * sin(rad * 5 - time);
            per_pixel_2=zoom = 1.01 + 0.012 * cos(ang * 3 + time) + 0.01 * rad;
        """,
        "Lissajous Garden" to """
            fDecay=0.93
            nWaveMode=2
            bWaveThick=1
            bAdditiveWaves=1
            fWaveScale=1.6
            fVideoEchoAlpha=0.35
            fVideoEchoZoom=1.1
            nVideoEchoOrientation=1
            per_frame_1=dx = 0.002 * sin(time * 0.5);
            per_frame_2=dy = 0.002 * cos(time * 0.37);
            per_frame_3=wave_r = 0.3 + 0.3 * sin(time * 0.6);
            per_frame_4=wave_g = 0.9;
            per_frame_5=wave_b = 0.5 + 0.5 * cos(time * 0.8);
            per_frame_6=zoom = 1.005 + 0.01 * max(bass - 1, 0);
        """,
        "Spectrum Rain" to """
            fDecay=0.94
            nWaveMode=3
            bAdditiveWaves=1
            fWaveScale=1.0
            warp=0
            per_frame_1=dy = -0.004 - 0.004 * max(mid - 1, 0);
            per_frame_2=wave_r = 0.3 + 0.7 * bass_att * 0.5;
            per_frame_3=wave_g = 0.4;
            per_frame_4=wave_b = 0.9 - 0.3 * sin(time * 0.3);
        """,
        "Mirror Ocean" to """
            fDecay=0.97
            nWaveMode=4
            bWaveThick=1
            fWaveScale=1.5
            fVideoEchoAlpha=0.5
            fVideoEchoZoom=1.0
            nVideoEchoOrientation=3
            warp=3
            per_frame_1=wave_y = 0.5 + 0.1 * sin(time * 0.3);
            per_frame_2=wave_r = 0.2;
            per_frame_3=wave_g = 0.6 + 0.3 * sin(time * 0.5);
            per_frame_4=wave_b = 1;
            per_frame_5=zoom = 1.004;
        """,
        "Kaleido Scope" to """
            fDecay=0.96
            nWaveMode=5
            bAdditiveWaves=1
            bWaveThick=1
            fWaveScale=1.8
            fVideoEchoAlpha=0.5
            nVideoEchoOrientation=1
            per_frame_init_1=q1 = 0;
            per_frame_1=q1 = q1 + 0.02 * bass_att;
            per_frame_2=rot = 0.03 * sin(q1);
            per_frame_3=zoom = 1.01 + 0.02 * max(bass - 1.2, 0);
            per_frame_4=wave_r = 0.5 + 0.5 * sin(q1 * 2);
            per_frame_5=wave_g = 0.5 + 0.5 * sin(q1 * 2 + 2.1);
            per_frame_6=wave_b = 0.5 + 0.5 * sin(q1 * 2 + 4.2);
        """,
        "Ember Lines" to """
            fDecay=0.9
            nWaveMode=7
            fWaveAlpha=0.35
            fWaveScale=1.3
            bAdditiveWaves=1
            ob_size=0.015
            ob_r=0.4
            ob_g=0.1
            ob_b=0
            ob_a=0.6
            per_frame_1=wave_r = 1;
            per_frame_2=wave_g = 0.45 + 0.25 * sin(time * 1.5);
            per_frame_3=wave_b = 0.1;
            per_frame_4=sy = 1.004 + 0.004 * max(treb - 1, 0);
            per_frame_5=dy = 0.003;
        """,
    )
}
