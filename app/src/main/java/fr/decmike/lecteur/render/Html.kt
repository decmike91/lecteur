package fr.decmike.lecteur.render

object Html {

    fun esc(s: CharSequence): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private const val CSS = """
body{margin:0;padding:12px;font-family:sans-serif;font-size:16px;line-height:1.45;color:#202124;background:#fff;overflow-wrap:break-word}
body.gray{background:#5f6368;padding:8px 2vw}
.doc{max-width:860px;margin:0 auto}
.p{margin:0 0 .55em;min-height:1em}
h1,h2,h3,h4,h5,h6{margin:.8em 0 .4em;line-height:1.25}
img{max-width:100%;height:auto}
table{border-collapse:collapse;margin:8px 0}
td,th{border:1px solid #c4c7c5;padding:4px 6px;vertical-align:top}
td .p{margin:0}
pre.txt{white-space:pre-wrap;word-break:break-word;font-family:monospace;font-size:14px;margin:0}
.msg{padding:28px 16px;text-align:center;color:#5f6368}
body.gray .msg{color:#eee}
.msgicon{font-size:48px}
.nav{position:sticky;top:0;z-index:9;background:#f1f3f4;padding:8px;margin:-12px -12px 8px;white-space:nowrap;overflow-x:auto;border-bottom:1px solid #ddd}
.nav a{display:inline-block;margin-right:6px;padding:6px 12px;border-radius:16px;background:#fff;border:1px solid #c4c7c5;color:#0b57d0;text-decoration:none;font-size:14px}
h2.sname{font-size:17px;margin:16px 0 6px;color:#0b57d0}
.tw{overflow-x:auto}
table.sheet{font-size:14px}
table.sheet td{white-space:pre-wrap;min-width:40px;max-width:420px}
table.sheet td.n{text-align:right;white-space:nowrap}
table.sheet th{background:#f1f3f4;color:#5f6368;font-weight:normal;font-size:12px}
table.sheet th.rn{text-align:right}
.pdfpage{display:block;width:100%;height:auto;margin:0 auto 10px;background:#fff;box-shadow:0 1px 4px rgba(0,0,0,.5)}
img.single{display:block;margin:0 auto;max-width:100%;background:#fff}
.slabel{color:#e8eaed;font-size:13px;margin:10px 0 4px}
.slide{position:relative;width:96vw;background:#fff;color:#000;overflow:hidden;box-shadow:0 1px 4px rgba(0,0,0,.5);line-height:1.15}
.shape{position:absolute;box-sizing:border-box;overflow:visible;overflow-wrap:break-word}
.shape p{margin:0}
.shape .bu{margin-right:.3em}
.ptbl{width:100%;margin:0}
.ptbl td{border:1px solid #999;padding:.3em}
.card{background:#fff;border-radius:6px;padding:14px;margin:0 auto 12px;max-width:860px;box-shadow:0 1px 4px rgba(0,0,0,.4)}
.card .slabel{color:#5f6368;margin:0 0 8px}
.num{display:inline-block;min-width:1.2em}
.note{background:#fff8e1;border:1px solid #ffe082;border-radius:6px;padding:8px 12px;margin:8px 0;font-size:14px;color:#5d4037}
"""

    fun page(body: CharSequence, bodyClass: String = ""): String {
        val sb = StringBuilder(body.length + 4096)
        sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, minimum-scale=0.1, maximum-scale=10, user-scalable=yes\">")
        sb.append("<style>").append(CSS).append("</style></head><body class=\"").append(bodyClass).append("\">")
        sb.append(body)
        sb.append("</body></html>")
        return sb.toString()
    }

    fun message(title: String, detail: String? = null): String {
        val d = if (detail.isNullOrBlank()) "" else "<p>${esc(detail)}</p>"
        return page("<div class=\"msg\"><div class=\"msgicon\">&#128196;</div><h2>${esc(title)}</h2>$d</div>")
    }

    fun note(text: String) = "<div class=\"note\">${esc(text)}</div>"
}
