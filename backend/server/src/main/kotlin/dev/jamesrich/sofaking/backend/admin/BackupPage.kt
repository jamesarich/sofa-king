package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.sameSecret
import dev.jamesrich.sofaking.backend.store.BackupFile
import dev.jamesrich.sofaking.backend.store.BackupKind
import dev.jamesrich.sofaking.backend.store.DatabaseBackups
import dev.jamesrich.sofaking.backend.store.RestoreRefused
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.html.ButtonType
import kotlinx.html.FORM
import kotlinx.html.FlowContent
import kotlinx.html.FormEncType
import kotlinx.html.FormMethod
import kotlinx.html.TBODY
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.caption
import kotlinx.html.checkBoxInput
import kotlinx.html.code
import kotlinx.html.div
import kotlinx.html.fileInput
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.hiddenInput
import kotlinx.html.id
import kotlinx.html.label
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr

/** The largest database the portal takes as an upload to restore. */
internal const val RESTORE_UPLOAD_LIMIT = 8L * 1024 * 1024 * 1024

/** The backups in the folder, where it is, and the backup a restore asks about, when it asks. */
internal class BackupView(
    val backups: List<BackupFile>,
    val directory: String,
    val keep: Int,
    val confirm: String? = null,
)

/** The System section's Backup page: the backups, one now, a download, and a restore from either. */
internal class BackupPages(private val portal: Portal) {
    fun install(route: Route) = with(portal) {
        route.get("/system/backup") { call.html(backupPage(chrome(), view())) }
        route.action("/system/backup/take") { session, _ ->
            val backup = backups().take()
            session.flash.set(Flash("Backed up the database as ${backup.name}.", target = backup.name))
            seeOther(call, "/admin/system/backup")
        }
        route.get("/system/backup/files/{name}") {
            call.session()
            val file = backups().file(call.parameters["name"].orEmpty()) ?: throw notFound()
            val disposition = ContentDisposition.Attachment
                .withParameter(ContentDisposition.Parameters.FileName, file.fileName.toString())
            call.response.header(HttpHeaders.ContentDisposition, disposition.toString())
            call.respondFile(file.toFile())
        }
        route.action("/system/backup/restore") { session, form ->
            val name = form["name"].orEmpty()
            if (form["confirm"] != "yes") {
                call.html(backupPage(chrome(), view(confirm = name)))
                return@action
            }
            restored(session, "the backup $name") { backups().restore(name) }
        }
        route.post("/system/backup/upload") { upload() }
    }

    /** Restores the upload once the form's token and its confirmation box have come before the file. */
    private suspend fun RoutingContext.upload() {
        val session = call.session()
        val backups = backups()
        // A body that is not a form upload carries no token, so it fails the token check like any other form.
        if (!call.request.contentType().match(ContentType.MultiPart.FormData)) {
            call.respondText("The form expired; reload the page and try again", status = HttpStatusCode.Forbidden)
            return
        }
        var csrf: String? = null
        var confirmed = false
        var sent = false
        var refused: String? = null
        call.receiveMultipart(formFieldLimit = RESTORE_UPLOAD_LIMIT).forEachPart { part ->
            when (part) {
                is PartData.FormItem -> when (part.name) {
                    "csrf" -> csrf = part.value
                    "confirm" -> confirmed = part.value == "yes"
                }

                is PartData.FileItem -> if (!sent && sameSecret(csrf, session.csrf) && confirmed) {
                    sent = true
                    refused = refusal { backups.restore(part.provider().toInputStream()) }
                }

                else -> Unit
            }
            part.release()
        }
        when {
            !sameSecret(csrf, session.csrf) ->
                call.respondText("The form expired; reload the page and try again", status = HttpStatusCode.Forbidden)

            !sent || refused != null -> {
                session.flash.set(Flash(refused ?: "Choose a file and tick the box to restore it.", Tone.BAD))
                seeOther(call, "/admin/system/backup#restore")
            }

            else -> call.html(restoringPage(portal.theme(call), "the uploaded database"))
        }
    }

    private suspend fun RoutingContext.restored(session: AdminSession, what: String, restore: suspend () -> Unit) {
        val refused = refusal(restore)
        if (refused == null) {
            call.html(restoringPage(portal.theme(call), what))
        } else {
            session.flash.set(Flash(refused, Tone.BAD))
            seeOther(call, "/admin/system/backup")
        }
    }

    private suspend fun view(confirm: String? = null): BackupView {
        val backups = backups()
        return BackupView(backups.list(), backups.directory.toAbsolutePath().toString(), backups.keep, confirm)
    }

    private fun backups(): DatabaseBackups = portal.runtime.backups ?: throw notFound()

    private fun notFound() = ApiFailure(HttpStatusCode.NotFound, "RESOURCE_NOT_FOUND", "Resource not found")
}

/** Why [restore] was refused, or null when it went ahead. */
private suspend fun refusal(restore: suspend () -> Unit): String? = try {
    restore()
    null
} catch (e: RestoreRefused) {
    e.message
}

internal fun backupPage(chrome: Chrome, view: BackupView): String = layout("Backup", chrome, "/admin/system/backup") {
    pageHead(
        "Backup",
        "Copies of the database, taken while the server runs: one a day, and one whenever you ask. Download one " +
            "to keep it elsewhere, or restore one; a restore restarts the server.",
    )
    tabs(chrome, "/admin/system/backup", SYSTEM_TABS)
    backupsPanel(chrome, view)
    restorePanel(chrome)
    view.confirm?.let { name ->
        askDialog(
            chrome,
            "Restore $name?",
            "/admin/system/backup/restore",
            "/admin/system/backup",
            "Restore",
            danger = true,
        ) {
            hiddenInput(name = "name") { value = name }
            restoreWarning()
        }
    }
}

private fun FlowContent.backupsPanel(chrome: Chrome, view: BackupView) = panel(
    "Backups",
    id = "backups",
    aside = {
        postForm("/admin/system/backup/take", chrome.session) {
            button(type = ButtonType.submit, classes = "small") { +"Back up now" }
        }
    },
) {
    p("muted mt-0") {
        +"In "
        code { +view.directory }
        +(
            ". The daily backup keeps its newest ${view.keep}; the rest stay until you remove them from the folder. " +
                "The updater's backups before each update land here too. A backup holds the database alone, the " +
                "generated tokens and provider sessions included, so keep copies private."
            )
    }
    if (view.backups.isEmpty()) {
        p("muted") { +"No backup yet. The first daily one runs ten minutes after the server starts." }
        return@panel
    }
    div("scroll") {
        table {
            caption("sr") { +"Database backups" }
            thead {
                tr {
                    col("File")
                    col("Kind")
                    col("Taken")
                    col("Size", "num")
                    col("Action")
                }
            }
            tbody {
                for (backup in view.backups) backupRow(chrome, backup)
            }
        }
    }
}

private fun TBODY.backupRow(chrome: Chrome, backup: BackupFile) = tr(flashClass(chrome, backup.name)) {
    th {
        attributes["scope"] = "row"
        span("mono small") { +backup.name }
    }
    td("muted") { +backup.kind.label }
    td("nowrap") { ago(backup.takenAt.toString(), chrome.now) }
    td("num nowrap") { +bytesText(backup.bytes) }
    td("nowrap") {
        a("/admin/system/backup/files/${backup.name}", classes = "btn small quiet") {
            attributes["download"] = backup.name
            +"Download"
        }
        +" "
        if (backup.kind != BackupKind.OTHER) {
            postForm("/admin/system/backup/restore", chrome.session) {
                hiddenInput(name = "name") { value = backup.name }
                button(type = ButtonType.submit, classes = "small") {
                    attributes["aria-label"] = "Restore ${backup.name}"
                    +"Restore"
                }
            }
        }
    }
}

private fun FlowContent.restorePanel(chrome: Chrome) = panel("Restore from a file", id = "restore") {
    form(
        action = "/admin/system/backup/upload",
        method = FormMethod.post,
        encType = FormEncType.multipartFormData,
    ) {
        // The token and the confirmation come before the file, so the server reads them first.
        hiddenInput(name = "csrf") { value = chrome.session.csrf }
        restoreWarning()
        div("field") {
            label {
                htmlFor = "restore-file"
                +"Database file"
            }
            fileInput(name = "file") {
                id = "restore-file"
                accept = ".sqlite,.db,application/vnd.sqlite3"
                required = true
            }
        }
        div("field") {
            label {
                checkBoxInput(name = "confirm") {
                    value = "yes"
                    required = true
                }
                +" Replace the database with this file and restart"
            }
        }
        button(type = ButtonType.submit, classes = "danger") { +"Restore" }
    }
}

private fun FORM.restoreWarning() = p {
    +(
        "The server checks the file, sets the database it runs aside as a before-restore backup, and restarts on " +
            "the restored one. Streams stop for a few seconds, everything since the backup was taken is gone, and " +
            "you may have to sign in again."
        )
}

/** The page a restore answers while the server goes down to swap the database in. */
internal fun restoringPage(theme: String?, what: String): String =
    layout("Restarting", null, null, theme, refreshSeconds = 20) {
        div("panel signin") {
            h1("mt-0") { +"Restarting" }
            p { +"Restoring $what. The server is restarting on it and is back in a few seconds." }
            p("muted") {
                +(
                    "This page opens the portal again in 20 seconds; sign in again if the restored database asks. " +
                        "If the server does not come back, its log says why, and the database it ran before is in " +
                        "the backups folder as a before-restore backup."
                    )
            }
            a("/admin", classes = "btn primary") { +"Open the portal" }
        }
    }
