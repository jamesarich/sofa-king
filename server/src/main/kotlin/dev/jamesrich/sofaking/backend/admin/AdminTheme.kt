package dev.jamesrich.sofaking.backend.admin

import java.time.Duration
import java.time.Instant
import kotlinx.html.*
import kotlinx.html.stream.createHTML

/**
 * What every signed-in page carries besides its content: the session, the render time, a one-shot notice, nav counts,
 * and the server revision and start time for the footer.
 */
internal class Chrome(
    val session: AdminSession, val now: Instant, val flash: Flash? = null, val badges: Map<String, Int> = emptyMap(),
    val revision: String? = null, val startedAt: Instant? = null,
)

/** A running time as "3 d 4 h", "5 h 12 min" or "7 min". */
internal fun uptime(from: Instant, now: Instant): String {
    val s = Duration.between(from, now).seconds.coerceAtLeast(0)
    return when {
        s >= 86_400 -> "${s / 86_400} d ${s % 86_400 / 3600} h"
        s >= 3600 -> "${s / 3600} h ${s % 3600 / 60} min"
        else -> "${s / 60} min"
    }
}

/** A notice shown once after a redirect; [target] names the row it concerns. */
internal class Flash(val message: String, val tone: Tone = Tone.OK, val target: String? = null)

internal enum class Tone(val css: String) { OK("ok"), WARN("warn"), BAD("bad"), OFF("off") }

/**
 * The brand palette of the TV app as tokens. Text pairs measured against WCAG 2.1: dusk on plum 16.4:1, on raised
 * 13.0:1; mauve on plum 10.1:1, on raised 8.0:1; amber on plum 10.6:1; plum on amber 10.6:1; each soft tone on its
 * deep tone 10.1:1; outline against plum 5.1:1 and raised 4.1:1 for control borders.
 */
private const val STYLE = """
:root{color-scheme:dark;
--plum:#14111F;--plum-low:#1F1A30;--plum-raised:#2A2440;--line:#3A3354;
--dusk:#F4EFFA;--mauve:#C4BAD9;--outline:#8C80A8;
--amber:#FFB547;--amber-deep:#3D2B12;--amber-soft:#FFD9A0;
--lavender:#C9B8FF;--lavender-deep:#332A52;--lavender-soft:#E6DDFF;
--error:#FFB4AB;--error-deep:#5C1A14;--error-soft:#FFDAD6;
--radius:10px;--gutter:16px}
*{box-sizing:border-box}
html{-webkit-text-size-adjust:100%}
body{margin:0;background:var(--plum);color:var(--dusk);font:15px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;min-height:100vh;overflow-x:hidden}
a{color:var(--amber);text-underline-offset:2px}
a:hover{color:var(--amber-soft)}
:focus-visible{outline:2px solid var(--amber);outline-offset:2px;border-radius:4px}
.skip{position:absolute;left:-9999px;top:8px;background:var(--amber);color:var(--plum);padding:8px 12px;border-radius:6px;z-index:20}
.skip:focus{left:var(--gutter)}
.top{border-bottom:1px solid var(--line);background:linear-gradient(180deg,#1B1629,var(--plum))}
.top-in,main,footer{max-width:1200px;margin:0 auto;padding-left:var(--gutter);padding-right:var(--gutter)}
.top-in{display:flex;flex-wrap:wrap;align-items:center;gap:10px 24px;padding-top:12px;padding-bottom:12px}
.brand{display:flex;align-items:center;gap:10px;color:var(--dusk);text-decoration:none;font-weight:700;font-size:1.05rem;letter-spacing:.01em}
.brand svg{width:34px;height:34px;flex:none}
.brand small{color:var(--mauve);font-weight:500;font-size:.85rem}
.signout{margin-left:auto}
nav.main{display:flex;flex-wrap:wrap;gap:6px 22px;align-items:center;flex:1 1 auto}
.navgroup{display:flex;flex-wrap:wrap;align-items:center;gap:2px 4px}
.navlabel{color:var(--mauve);font-size:.72rem;font-weight:700;letter-spacing:.08em;text-transform:uppercase;margin-right:4px}
nav.main a{color:var(--dusk);text-decoration:none;padding:6px 10px;border-radius:999px;display:inline-flex;align-items:center;gap:6px;min-height:36px}
nav.main a:hover{background:var(--plum-raised)}
nav.main a[aria-current]{background:var(--amber-deep);color:var(--amber-soft);box-shadow:inset 0 0 0 1px #6B4B1F}
.count{background:var(--amber);color:var(--plum);border-radius:999px;font-size:.72rem;font-weight:700;padding:0 6px;line-height:18px;min-width:18px;text-align:center}
main{padding-top:8px;padding-bottom:32px}
footer{color:var(--mauve);padding-top:8px;padding-bottom:28px;font-size:.85rem}
h1{font-size:1.55rem;line-height:1.25;margin:20px 0 4px}
h2{font-size:1.1rem;margin:0 0 10px}
h3{font-size:.95rem;margin:16px 0 8px;color:var(--mauve)}
.lede{color:var(--mauve);margin:0 0 18px;max-width:70ch}
p{max-width:75ch}
.panel{background:var(--plum-low);border:1px solid var(--line);border-radius:var(--radius);padding:16px;margin:0 0 16px;min-width:0}
.panel>h2:first-child{display:flex;align-items:baseline;justify-content:space-between;gap:12px;flex-wrap:wrap}
.panel h2 a,.panel h2 .aside{font-size:.85rem;font-weight:500}
.cols{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,340px),1fr));gap:16px;align-items:start}
.cols>.panel{margin:0}
.stack>*+*{margin-top:16px}
.muted{color:var(--mauve)}
.small{font-size:.85rem}
.nowrap{white-space:nowrap}
.num{font-variant-numeric:tabular-nums}
code,.mono{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:.88em}
code{word-break:break-all;background:var(--plum);border:1px solid var(--line);padding:1px 5px;border-radius:5px}
.tiles{display:grid;grid-template-columns:repeat(auto-fill,minmax(min(100%,300px),1fr));gap:12px;margin:0 0 16px}
.tile{display:block;background:var(--plum-low);border:1px solid var(--line);border-radius:var(--radius);padding:14px 16px;color:var(--dusk);text-decoration:none;min-width:0}
a.tile:hover{border-color:var(--outline);color:var(--dusk);background:#241E38}
.tile .label{color:var(--mauve);font-size:.8rem;font-weight:600;letter-spacing:.04em;text-transform:uppercase}
.tile .value{display:block;font-size:1.7rem;font-weight:700;line-height:1.2;margin:4px 0 2px;font-variant-numeric:tabular-nums}
.tile .value small{font-size:.95rem;color:var(--mauve);font-weight:500}
.tile .sub{color:var(--mauve);font-size:.85rem}
.tile.warn{border-color:#6B4B1F}.tile.warn .value{color:var(--amber)}
.tile.bad{border-color:#8A3A30}.tile.bad .value{color:var(--error)}
.meter{height:6px;background:var(--plum-raised);border-radius:999px;overflow:hidden;margin-top:8px}
.meter span{display:block;height:100%;background:var(--lavender)}
.chip{display:inline-block;border-radius:999px;padding:1px 9px;font-size:.78rem;font-weight:600;line-height:20px;white-space:nowrap;border:1px solid transparent}
.chip.ok{background:var(--lavender-deep);color:var(--lavender-soft)}
.chip.warn{background:var(--amber-deep);color:var(--amber-soft)}
.chip.bad{background:var(--error-deep);color:var(--error-soft)}
.chip.off{background:transparent;color:var(--mauve);border-color:var(--outline)}
.attention{list-style:none;margin:0;padding:0}
.attention li{display:grid;grid-template-columns:1fr auto;gap:6px 16px;align-items:center;padding:12px 0 12px 14px;border-top:1px solid var(--line);position:relative}
.attention li::before{content:"";position:absolute;left:0;top:12px;bottom:12px;width:4px;border-radius:2px;background:var(--outline)}
.attention li.bad::before{background:var(--error)}.attention li.warn::before{background:var(--amber)}
.attention li:first-child{border-top:0}
.attention .what{min-width:0}
.attention .what b{font-weight:600}
.attention .what .chip{margin-right:8px;vertical-align:1px}
.attention .what span.muted{display:block}
.attention .fix{white-space:nowrap}
.allgood{display:flex;gap:12px;align-items:center;color:var(--lavender-soft)}
.allgood::before{content:"";width:12px;height:12px;border-radius:50%;background:var(--lavender);box-shadow:0 0 0 4px var(--lavender-deep);flex:none}
.scroll{overflow-x:auto;-webkit-overflow-scrolling:touch;margin:0 -4px;padding:0 4px}
table{width:100%;border-collapse:collapse}
th,td{text-align:left;padding:9px 10px;border-bottom:1px solid var(--line);vertical-align:middle}
thead th{color:var(--mauve);font-size:.75rem;font-weight:700;letter-spacing:.06em;text-transform:uppercase;white-space:nowrap}
tbody tr:last-child>*{border-bottom:0}
tbody tr:hover{background:#1A1528}
th[scope=row]{font-weight:600}
td.num,th.num{text-align:right}
tr.flash>*{background:#262036}
tr.flash>:first-child{box-shadow:inset 4px 0 0 var(--lavender)}
tr.err-row>*{background:#2E1A22}
tr.err-row>:first-child{box-shadow:inset 4px 0 0 var(--error)}
a.rowlink{color:var(--dusk);text-decoration-color:var(--outline)}
a.rowlink:hover{color:var(--amber);text-decoration-color:var(--amber)}
input,select,button,.btn{font:inherit;color:var(--dusk);border-radius:8px;min-height:38px}
input,select{background:var(--plum);border:1px solid var(--outline);padding:6px 10px;max-width:100%}
input::placeholder{color:#A69CC0}
input[type=checkbox]{min-height:0;width:18px;height:18px;accent-color:var(--amber);vertical-align:middle}
button,.btn{display:inline-flex;align-items:center;justify-content:center;gap:6px;cursor:pointer;background:var(--plum-raised);border:1px solid var(--outline);padding:6px 14px;text-decoration:none;font-weight:600;white-space:nowrap}
button:hover,.btn:hover{border-color:var(--lavender);color:var(--dusk)}
.btn.primary,button.primary{background:var(--amber);color:var(--plum);border-color:var(--amber)}
.btn.primary:hover,button.primary:hover{background:var(--amber-soft);border-color:var(--amber-soft);color:var(--plum)}
button.danger,.btn.danger{background:var(--error-deep);color:var(--error-soft);border-color:#8A3A30}
button.danger:hover{border-color:var(--error)}
button.quiet,.btn.quiet{background:transparent;border-color:transparent;color:var(--amber);padding-left:8px;padding-right:8px}
button.quiet:hover,.btn.quiet:hover{border-color:var(--line)}
button.small,.btn.small{min-height:32px;padding:3px 10px;font-size:.85rem}
form.inline{display:inline}
.actions{display:flex;flex-wrap:wrap;gap:6px;align-items:center}
.field{display:flex;flex-direction:column;gap:4px;min-width:0}
.field label,.field .label{font-size:.8rem;font-weight:600;color:var(--mauve)}
.field .hint{font-size:.8rem;color:var(--mauve)}
.spaced{margin-top:14px}
.maprow{display:grid;grid-template-columns:minmax(120px,200px) 1fr;gap:8px 16px;align-items:start;padding:10px 0;border-top:1px solid var(--line)}
.maprow:first-child{border-top:0}
.mapgroup{font-weight:600;padding-top:4px}
@media (max-width:720px){.maprow{grid-template-columns:1fr}}
.formgrid{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,180px),1fr));gap:12px;align-items:end}
.filters{display:grid;grid-template-columns:minmax(0,2fr) repeat(3,minmax(0,1fr)) auto auto;gap:10px;align-items:end;margin:0 0 12px}
.filters .check{display:flex;align-items:center;gap:8px;min-height:38px;white-space:nowrap}
.summary{display:flex;flex-wrap:wrap;justify-content:space-between;gap:8px;align-items:center;color:var(--mauve);margin:0 0 8px}
.pager{display:flex;gap:8px;align-items:center;justify-content:center;margin:14px 0 0;flex-wrap:wrap}
.pager .btn[aria-disabled]{opacity:.45;pointer-events:none}
.notice{background:var(--error-deep);color:var(--error-soft);border:1px solid #8A3A30;border-radius:var(--radius);padding:12px 14px;margin:0 0 16px}
.notice.warn{background:var(--amber-deep);color:var(--amber-soft);border-color:#6B4B1F}
.notice ul{margin:6px 0 0;padding-left:20px}
.inline-error{color:var(--error);font-size:.85rem;margin-top:4px}
.kv{display:grid;grid-template-columns:minmax(120px,max-content) 1fr;gap:6px 16px;margin:0}
.kv dt{color:var(--mauve)}
.kv dd{margin:0;min-width:0;overflow-wrap:anywhere}
.settings{display:grid;gap:12px;margin:0}
.setting .name{color:var(--mauve);font-size:.78rem;letter-spacing:.02em}
.setting .val{margin-top:2px;overflow-wrap:anywhere}
.chips{display:flex;flex-wrap:wrap;gap:6px}
.tag{display:inline-flex;align-items:center;gap:2px;background:var(--plum-raised);border:1px solid var(--line);border-radius:999px;padding:0 2px 0 10px;font-size:.85rem;min-height:30px}
.tag button{min-height:26px;padding:0 8px;background:transparent;border:0;color:var(--mauve);font-weight:700}
.tag button:hover{color:var(--error)}
details>summary{cursor:pointer;color:var(--amber);font-weight:600;min-height:32px;display:flex;align-items:center}
.toast{position:fixed;left:50%;bottom:20px;transform:translateX(-50%);z-index:30;max-width:min(560px,calc(100vw - 32px));background:var(--plum-raised);color:var(--dusk);border:1px solid var(--outline);border-left:4px solid var(--lavender);border-radius:var(--radius);padding:12px 16px;box-shadow:0 10px 30px rgba(0,0,0,.45);animation:toast 7s ease forwards}
.toast.warn{border-left-color:var(--amber)}.toast.bad{border-left-color:var(--error)}
@keyframes toast{0%,85%{opacity:1;visibility:visible}100%{opacity:0;visibility:hidden}}
@media (prefers-reduced-motion:reduce){.toast{animation:none}}
.modal{position:fixed;inset:0;z-index:40;background:rgba(9,7,15,.78);display:flex;align-items:flex-start;justify-content:center;padding:8vh var(--gutter) var(--gutter);overflow-y:auto}
.dialog{background:var(--plum-low);border:1px solid var(--outline);border-radius:14px;max-width:620px;width:100%;padding:20px;box-shadow:0 20px 60px rgba(0,0,0,.6)}
.dialog h2{font-size:1.2rem;margin:0 0 8px}
.dialog ul{padding-left:20px;margin:8px 0}
.dialog .actions{margin-top:16px;justify-content:flex-end}
@media (max-width:720px){
  body{font-size:15px}
  h1{font-size:1.35rem;margin-top:16px}
  .top-in{gap:8px 12px}
  nav.main{order:3;flex-basis:100%;gap:2px 2px}
  .navgroup{display:contents}
  .navlabel{margin:0 2px 0 10px}
  nav.main a{padding:6px 8px}
  table.stack tr.flash>*,table.stack tr.err-row>*{box-shadow:none}
  table.stack tr.flash{box-shadow:inset 4px 0 0 var(--lavender);background:#262036;padding-left:10px}
  table.stack tr.err-row{box-shadow:inset 4px 0 0 var(--error);background:#2E1A22;padding-left:10px}
  .filters{grid-template-columns:1fr 1fr}
  .filters .wide{grid-column:1/-1}
  .panel{padding:14px 12px}
  table.stack thead{display:none}
  table.stack,table.stack tbody,table.stack tr,table.stack td,table.stack th{display:block;width:auto}
  table.stack tr{display:grid;grid-template-columns:auto 1fr auto;gap:2px 10px;padding:10px 0;border-bottom:1px solid var(--line)}
  table.stack td,table.stack th{border:0;padding:0}
  table.stack [data-cell=number]{grid-row:1;grid-column:1;font-weight:700;color:var(--amber)}
  table.stack [data-cell=name]{grid-row:1;grid-column:2;min-width:0}
  table.stack [data-cell=state]{grid-row:1;grid-column:3;text-align:right}
  table.stack [data-cell=meta]{grid-column:2/4;color:var(--mauve);font-size:.85rem}
  table.stack [data-cell=actions]{grid-column:2/4;margin-top:6px}
  table.stack .wide-only{display:none}
}
@media (min-width:721px){table.stack .narrow-only{display:none}}
"""

/** The crown-on-a-sofa mark of the TV app's banner, drawn inline so it needs no image request. */
private const val MARK = """<svg viewBox="0 0 120 120" aria-hidden="true" focusable="false"><path fill="#B3A1EA" d="M22,46 Q22,40 28,40 L92,40 Q98,40 98,46 L98,70 L22,70 Z"/><path fill="#C9B8FF" d="M10,62 Q10,58 14,58 L106,58 Q110,58 110,62 L110,92 L10,92 Z"/><path fill="#C9B8FF" d="M6,58 Q6,52 12,52 L20,52 L20,96 L6,96 Z"/><path fill="#C9B8FF" d="M100,52 L108,52 Q114,52 114,58 L114,96 L100,96 Z"/><path fill="#9C8AD4" d="M20,72 L100,72 L100,75 L20,75 Z M14,96 L14,104 L20,104 L20,96 Z M100,96 L100,104 L106,104 L106,96 Z"/><path fill="#FFB547" d="M36,46 L36,30 L48,38 L60,20 L72,38 L84,30 L84,46 Z"/><path fill="#E0902A" d="M36,40 L84,40 L84,46 L36,46 Z"/><path fill="#14111F" d="M60,42 L62,44 L60,46 L58,44 Z"/></svg>"""

private const val HTMX_CONFIG =
    """{"allowEval":false,"allowScriptTags":false,"includeIndicatorStyles":false,"historyCacheSize":0,"refreshOnHistoryMiss":true,""" +
        """"responseHandling":[{"code":"204","swap":false},{"code":"[23]..","swap":true},{"code":"4..","swap":true,"error":false},{"code":"...","swap":false,"error":true}]}"""

internal class NavItem(val href: String, val label: String, val badge: String? = null)

/** The portal's sections: the overview, then what the household watches, then how the server is set up. */
internal val NAV: List<Pair<String?, List<NavItem>>> = listOf(
    null to listOf(NavItem("/admin", "Overview")),
    "Lineup" to listOf(NavItem("/admin/lineup", "Channels"), NavItem("/admin/merges", "Merges", "merges"), NavItem("/admin/required", "Required", "required")),
    "Setup" to listOf(
        NavItem("/admin/groups", "Groups", "groups"), NavItem("/admin/providers", "Providers", "providers"), NavItem("/admin/plex", "Plex", "plex"),
        NavItem("/admin/settings", "Settings"),
    ),
)

/** The shared page: the brand bar, the grouped nav and sign-out for a signed-in [chrome], htmx boosting its links and forms. */
internal fun layout(title: String, chrome: Chrome?, current: String?, content: MAIN.() -> Unit): String = "<!DOCTYPE html>\n" + createHTML(prettyPrint = false).html {
    lang = "en"
    head {
        meta(charset = "utf-8")
        meta(name = "viewport", content = "width=device-width, initial-scale=1")
        meta(name = "theme-color", content = "#14111F")
        meta(name = "htmx-config", content = HTMX_CONFIG)
        title { +"$title · Sofa King admin" }
        link(rel = "icon", href = "/admin/static/icon.svg", type = "image/svg+xml")
        style { unsafe { +STYLE } }
        if (chrome != null) script(src = "/admin/static/htmx.min.js") {}
    }
    body {
        if (chrome != null) {
            attributes["hx-boost"] = "true"
            attributes["hx-headers"] = """{"X-CSRF-Token":"${chrome.session.csrf}"}"""
        }
        a("#content", classes = "skip") { +"Skip to content" }
        header("top") {
            div("top-in") {
                a(if (chrome != null) "/admin" else "/admin", classes = "brand") {
                    unsafe { +MARK }
                    span { +"Sofa King"; +" "; small { +"admin" } }
                }
                if (chrome != null) {
                    nav("main") {
                        attributes["aria-label"] = "Portal"
                        for ((label, items) in NAV) div("navgroup") {
                            if (label != null) span("navlabel") { +label }
                            for (item in items) a(item.href) {
                                if (item.href == current) attributes["aria-current"] = "page"
                                +item.label
                                val count = item.badge?.let { chrome.badges[it] } ?: 0
                                if (count > 0) span("count") { attributes["aria-label"] = "$count need attention"; +count.toString() }
                            }
                        }
                    }
                    postForm("/admin/logout", chrome.session, classes = "inline signout") { button(type = ButtonType.submit, classes = "quiet") { +"Sign out" } }
                }
            }
        }
        main {
            id = "content"
            content()
        }
        footer {
            +"Sofa King well organized."
            if (chrome?.revision != null) { +" Revision "; code { +chrome.revision }; +"." }
            if (chrome?.startedAt != null) +" Up ${uptime(chrome.startedAt, chrome.now)}."
        }
        val flash = chrome?.flash
        if (flash != null) div("toast ${flash.tone.css}") { attributes["role"] = "status"; +flash.message }
    }
}

/** A POST form with the CSRF token; [keepScroll] keeps the page where it is when htmx swaps the answer in. */
internal fun FlowContent.postForm(action: String, session: AdminSession, keepScroll: Boolean = false, classes: String = "inline", block: FORM.() -> Unit) =
    form(action = action, method = FormMethod.post, classes = classes) {
        // The stream writes a tag's attributes with its first child, so they are set before any.
        if (keepScroll) attributes["hx-swap"] = "innerHTML show:none"
        hiddenInput(name = "csrf") { value = session.csrf }
        block()
    }

internal fun FlowContent.pageHead(title: String, lede: String? = null) {
    h1 { +title }
    if (lede != null) p("lede") { +lede }
}

internal fun FlowContent.notice(message: String?, details: List<String> = emptyList(), tone: Tone = Tone.BAD) {
    if (message == null) return
    div(if (tone == Tone.WARN) "notice warn" else "notice") {
        attributes["role"] = "alert"
        +message
        if (details.isNotEmpty()) ul { for (line in details) li { +line } }
    }
}

internal fun FlowContent.chip(text: String, tone: Tone) = span("chip ${tone.css}") { +text }

internal fun FlowContent.panel(title: String?, id: String? = null, aside: (FlowContent.() -> Unit)? = null, block: DIV.() -> Unit) = section {
    if (id != null) this.id = id
    div("panel") {
        if (title != null) h2 {
            +title
            if (aside != null) span("aside") { aside() }
        }
        block()
    }
}

/** An instant as "12 min ago", with the exact time on hover; [never] when there is none. */
internal fun FlowContent.ago(iso: String?, now: Instant, never: String = "never") {
    val instant = iso?.let { runCatching { Instant.parse(it) }.getOrNull() }
    if (instant == null) { +(iso ?: never); return }
    time {
        attributes["datetime"] = iso
        attributes["title"] = iso
        +relative(instant, now)
    }
}

internal fun relative(instant: Instant, now: Instant): String {
    val seconds = Duration.between(instant, now).seconds
    val future = seconds < 0
    val s = kotlin.math.abs(seconds)
    val text = when {
        s < 60 -> return if (future) "in under a minute" else "just now"
        s < 3600 -> "${s / 60} min"
        s < 86_400 -> "${s / 3600} h"
        else -> "${s / 86_400} d"
    }
    return if (future) "in $text" else "$text ago"
}

internal fun thousands(n: Int): String = "%,d".format(java.util.Locale.US, n)

internal fun plural(n: Int, one: String, many: String = one + "s"): String = "${thousands(n)} ${if (n == 1) one else many}"
