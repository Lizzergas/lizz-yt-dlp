package com.lizz.ytdl.sample.terminal.presentation.components

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text

@Composable
fun AsciiPanel(
    title: String,
    width: Int,
    lines: List<String>,
    bodyRows: Int = lines.size.coerceAtLeast(1),
    modifier: Modifier = Modifier,
) {
    val innerWidth = (width - 4).coerceAtLeast(12)
    val wrapped = lines.flatMap { wrapToWidth(it, innerWidth) }.take(bodyRows)
    val padded = wrapped + List((bodyRows - wrapped.size).coerceAtLeast(0)) { "" }

    Column(modifier = modifier.width(width)) {
        Text(topBorder(title, innerWidth))
        padded.forEach { line ->
            Text("│ ${line.padEnd(innerWidth)} │")
        }
        Text(bottomBorder(innerWidth))
    }
}

private fun topBorder(title: String, innerWidth: Int): String {
    val trimmedTitle = title.take((innerWidth - 2).coerceAtLeast(1))
    val dashes = (innerWidth - trimmedTitle.length - 1).coerceAtLeast(0)
    return "┌─$trimmedTitle${"─".repeat(dashes)}┐"
}

private fun bottomBorder(innerWidth: Int): String = "└${"─".repeat(innerWidth + 2)}┘"

private fun wrapToWidth(text: String, width: Int): List<String> {
    if (text.length <= width) return listOf(text)

    val parts = mutableListOf<String>()
    var remaining = text.trim()
    while (remaining.length > width) {
        val breakIndex = remaining.lastIndexOf(' ', width).takeIf { it > 0 } ?: width
        parts += remaining.substring(0, breakIndex).trimEnd()
        remaining = remaining.substring(breakIndex).trimStart()
    }
    if (remaining.isNotEmpty()) parts += remaining
    return parts.ifEmpty { listOf("") }
}
