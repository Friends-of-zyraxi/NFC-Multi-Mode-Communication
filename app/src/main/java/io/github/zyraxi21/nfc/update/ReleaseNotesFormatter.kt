package io.github.zyraxi21.nfc.update

/** 复用记账本的更新说明规整方式，以纯文本展示标题、列表与强调内容。 */
internal object ReleaseNotesFormatter {
    fun format(raw: String): String {
        val lines = raw.replace("\r\n", "\n").replace('\r', '\n').lines()
        val formatted = ArrayList<String>()
        for (line in lines) {
            var text = line.trim()
            if (text.isEmpty()) {
                if (formatted.isNotEmpty() && formatted.last().isNotEmpty()) formatted.add("")
                continue
            }
            text = text.replace(Regex("^(?:#{1,6}\\s+|>\\s*|[-*+]\\s+)"), "")
                .replace("**", "").replace("`", "")
            formatted.add(text)
        }
        return formatted.joinToString("\n").trim()
    }
}
