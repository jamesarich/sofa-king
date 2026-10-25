package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.library.EncoderChecks
import dev.jamesrich.sofaking.backend.library.EncoderTrial
import dev.jamesrich.sofaking.backend.library.EncoderVerdict
import dev.jamesrich.sofaking.backend.library.HardwareAccel
import dev.jamesrich.sofaking.backend.library.TEST_CLIP_SECONDS
import dev.jamesrich.sofaking.backend.library.TRANSCODE_TEST_JOB
import dev.jamesrich.sofaking.backend.library.encoderName
import dev.jamesrich.sofaking.backend.library.speed
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.button
import kotlinx.html.caption
import kotlinx.html.code
import kotlinx.html.dd
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.dl
import kotlinx.html.dt
import kotlinx.html.hiddenInput
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.summary
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr

/** Where the About page's Run button comes back to. */
internal const val ENCODER_PANEL = "/admin/system/about#encoder"

/**
 * The About page's encoder panel: what `SOFA_HWACCEL` asked for, what the startup check found and uses, and the newest
 * transcode test with each encoder's speed, with a button that runs it again.
 */
internal fun FlowContent.encoderPanel(chrome: Chrome, checks: EncoderChecks) = panel("Video encoder", id = "encoder") {
    val startup = checks.startup
    dl("kv") {
        dt { +"SOFA_HWACCEL" }
        dd { code { +(startup?.requested ?: HardwareAccel.NONE).setting } }
        dt { +"In use" }
        dd {
            if (startup == null) {
                +"Not checked yet"
            } else {
                +encoderName(startup.chosen)
                if (startup.failed) {
                    +" "
                    chip("${encoderName(startup.requested)} failed", Tone.BAD)
                }
            }
        }
        if (startup != null && startup.requested != HardwareAccel.NONE) {
            dt { +"Device" }
            dd { code { +startup.device } }
            dt { +"Checked" }
            dd {
                ago(startup.at.toString(), chrome.now)
                +": "
                +startup.trials.joinToString { "${encoderName(it.accel)} ${if (it.ok) "works" else "failed"}" }
                    .ifEmpty { "no GPU device found" }
            }
        }
    }
    startup?.trials?.filter { !it.ok }?.forEach { trialDetails(it) }
    val test = checks.lastTest
    if (test == null) {
        p("muted") {
            +(
                "The transcode test encodes $TEST_CLIP_SECONDS seconds of a 1080p test picture through software and " +
                    "each GPU encoder there is, and says how much faster than real time each one is."
                )
        }
    } else {
        testTable(chrome, test)
    }
    postForm("/admin/system/jobs/$TRANSCODE_TEST_JOB/run", chrome.session, keepScroll = true) {
        hiddenInput(name = "back") { value = ENCODER_PANEL }
        div("actions") {
            button(type = ButtonType.submit) { +(if (test == null) "Run the transcode test" else "Run it again") }
            span("muted small") { +"It takes a few seconds per encoder; reload to see the result." }
        }
    }
}

private fun FlowContent.testTable(chrome: Chrome, test: EncoderVerdict) = div("scroll") {
    table {
        caption("sr") { +"Transcode test" }
        thead {
            tr {
                col("Encoder")
                col("Result")
                col("Speed")
            }
        }
        tbody {
            for (trial in test.trials) {
                tr {
                    th {
                        attributes["scope"] = "row"
                        +encoderName(trial.accel)
                        if (trial.accel == test.chosen) span("muted small") { +" in use" }
                    }
                    td {
                        if (trial.ok) chip("Works", Tone.OK) else chip("Failed", Tone.BAD)
                        trial.error?.let { span("muted small") { +" $it" } }
                    }
                    td("num") { +speed(trial, test.clipSeconds).ifEmpty { "–" } }
                }
            }
        }
    }
    p("muted small") {
        +"Tested "
        ago(test.at.toString(), chrome.now)
        +". Faster than 1.0x keeps up with playback; each viewer converting a file needs one."
    }
}

/** What FFmpeg said when [trial] failed, and the command that ran, folded. */
private fun FlowContent.trialDetails(trial: EncoderTrial) = details {
    summary { +"Why ${encoderName(trial.accel)} failed" }
    p { +(trial.error ?: "FFmpeg exited with an error.") }
    p { code { +trial.command } }
}

/** A Home attention item when the GPU encoder `SOFA_HWACCEL` names failed its check, so files convert in software. */
internal fun encoderAttention(checks: EncoderChecks?): List<Attention> {
    val startup = checks?.startup?.takeIf { it.failed } ?: return emptyList()
    val name = encoderName(startup.requested)
    val why = startup.trials.firstOrNull()?.error?.let { ": $it" }.orEmpty()
    return listOf(
        Attention(
            Tone.BAD,
            "The $name encoder does not work",
            "SOFA_HWACCEL=${startup.requested.setting}, but its test frame failed$why. Library files, virtual " +
                "channels and multiview convert in software, which is slower.",
            ENCODER_PANEL,
            "About",
            "encoder-failed",
            "libraries.md#hardware-transcoding",
        ),
    )
}
