package io.github.lookandhate.flowtrace.ui

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import io.github.lookandhate.flowtrace.model.CodeLine
import java.awt.Font

/**
 * Рисует строку кода с подсветкой синтаксиса из текущей схемы редактора и жирным выделенным фрагментом.
 * Лексер берётся по типу файла — без прямой зависимости от классов языка Go.
 */
internal class CodeLineRenderer(private val project: Project) {
    private data class Segment(val text: String, val attributes: TextAttributes?)

    private val cache = HashMap<Pair<String, String>, List<Segment>>()

    fun render(target: SimpleColoredComponent, code: CodeLine, selected: Boolean) {
        val highlight = code.highlight
        var offset = 0
        for (segment in segments(code)) {
            val end = offset + segment.text.length
            // Делим токен по границам выделения: внутри — жирным.
            val cuts = listOfNotNull(offset, highlight?.first, highlight?.last?.plus(1), end)
                .filter { it in offset..end }.distinct().sorted()
            for ((a, b) in cuts.zipWithNext()) {
                val bold = highlight != null && a >= highlight.first && b <= highlight.last + 1
                target.append(code.text.substring(a, b), attributes(segment.attributes, bold, selected))
            }
            offset = end
        }
    }

    private fun attributes(attrs: TextAttributes?, bold: Boolean, selected: Boolean): SimpleTextAttributes {
        val style = (if (bold) SimpleTextAttributes.STYLE_BOLD else SimpleTextAttributes.STYLE_PLAIN) or
            (if (attrs != null && attrs.fontType and Font.ITALIC != 0) SimpleTextAttributes.STYLE_ITALIC else 0)
        // В выделенной строке дерева цвета схемы нечитаемы — оставляем только начертание.
        val color = if (selected) null else attrs?.foregroundColor
        return SimpleTextAttributes(style, color)
    }

    private fun segments(code: CodeLine): List<Segment> {
        val fileType = code.fileType ?: return listOf(Segment(code.text, null))
        return cache.getOrPut(fileType.name to code.text) {
            val highlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(fileType, project, null)
                ?: return@getOrPut listOf(Segment(code.text, null))
            val scheme = EditorColorsManager.getInstance().globalScheme
            val lexer = highlighter.highlightingLexer
            lexer.start(code.text)
            val result = ArrayList<Segment>()
            var covered = 0
            while (lexer.tokenType != null) {
                val type = lexer.tokenType!!
                val attrs = highlighter.getTokenHighlights(type)
                    .fold(null as TextAttributes?) { acc, key -> TextAttributes.merge(acc, scheme.getAttributes(key)) }
                result += Segment(code.text.substring(lexer.tokenStart, lexer.tokenEnd), attrs)
                covered = lexer.tokenEnd
                lexer.advance()
            }
            if (covered < code.text.length) result += Segment(code.text.substring(covered), null)
            result
        }
    }
}
