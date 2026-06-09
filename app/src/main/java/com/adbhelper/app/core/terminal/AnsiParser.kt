package com.adbhelper.app.core.terminal

import androidx.compose.ui.graphics.Color

data class AnsiSpan(val text: String, val color: Color)

data class OutputLine(val spans: List<AnsiSpan>)

object AnsiParser {

    fun parse(raw: String): List<AnsiSpan> {
        val spans = mutableListOf<AnsiSpan>()
        val buffer = StringBuilder()
        var currentColor = Color.White
        var i = 0

        while (i < raw.length) {
            if (raw[i] == '' && i + 1 < raw.length && raw[i + 1] == '[') {
                // Flush buffer
                if (buffer.isNotEmpty()) {
                    spans.add(AnsiSpan(buffer.toString(), currentColor))
                    buffer.clear()
                }
                // Parse CSI sequence: ESC [ <params> <final_byte>
                i += 2
                val params = StringBuilder()
                while (i < raw.length && !raw[i].isLetter()) {
                    params.append(raw[i])
                    i++
                }
                if (i < raw.length) {
                    val finalByte = raw[i]
                    i++
                    if (finalByte == 'm') {
                        currentColor = parseSgrColor(params.toString(), currentColor)
                    }
                }
            } else if (raw[i] == '') {
                // Other escape sequences - skip
                if (buffer.isNotEmpty()) {
                    spans.add(AnsiSpan(buffer.toString(), currentColor))
                    buffer.clear()
                }
                i++
                while (i < raw.length && !raw[i].isLetter()) i++
                if (i < raw.length) i++
            } else if (raw[i].code in 0x20..0x7E || raw[i].code > 0x7F) {
                buffer.append(raw[i])
                i++
            } else {
                i++ // skip control characters
            }
        }

        if (buffer.isNotEmpty()) {
            spans.add(AnsiSpan(buffer.toString(), currentColor))
        }

        return spans
    }

    private fun parseSgrColor(params: String, current: Color): Color {
        if (params.isEmpty()) return Color.White
        val codes = params.split(";").map { it.toIntOrNull() ?: 0 }
        var color = current
        var bold = false
        for (code in codes) {
            when (code) {
                0 -> { color = Color.White; bold = false }
                1 -> bold = true
                30 -> color = if (bold) Color(0xFF555555) else Color(0xFF000000)
                31 -> color = if (bold) Color(0xFFFF5555) else Color(0xFFCC0000)
                32 -> color = if (bold) Color(0xFF55FF55) else Color(0xFF00CC00)
                33 -> color = if (bold) Color(0xFFFFFF55) else Color(0xFFCCCC00)
                34 -> color = if (bold) Color(0xFF5555FF) else Color(0xFF0000CC)
                35 -> color = if (bold) Color(0xFFFF55FF) else Color(0xFFCC00CC)
                36 -> color = if (bold) Color(0xFF55FFFF) else Color(0xFF00CCCC)
                37 -> color = if (bold) Color(0xFFFFFFFF) else Color(0xFFCCCCCC)
                90 -> color = Color(0xFF555555)
                91 -> color = Color(0xFFFF5555)
                92 -> color = Color(0xFF55FF55)
                93 -> color = Color(0xFFFFFF55)
                94 -> color = Color(0xFF5555FF)
                95 -> color = Color(0xFFFF55FF)
                96 -> color = Color(0xFF55FFFF)
                97 -> color = Color(0xFFFFFFFF)
            }
        }
        return color
    }
}
