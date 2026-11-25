package io.github.lookandhate.flowtrace.ui

import io.github.lookandhate.flowtrace.model.CodeLine
import io.github.lookandhate.flowtrace.model.Direction
import io.github.lookandhate.flowtrace.model.FlowEdge
import io.github.lookandhate.flowtrace.model.FlowGraph
import io.github.lookandhate.flowtrace.model.FlowKind
import io.github.lookandhate.flowtrace.model.FlowNode
import javax.swing.tree.DefaultMutableTreeNode

/** Строка дерева. */
internal sealed interface Row

/**
 * Строка кода, как в дереве Dataflow: номер, текст с выделенным использованием, функция.
 * [edge] — ребро, по которому пришли (место перехода), [node] — узел, чьи продолжения идут ниже.
 */
internal data class CodeRow(val code: CodeLine, val edge: FlowEdge?, val node: FlowNode?) : Row

/** Группа прямых использований одного типа. */
internal data class KindRow(val kind: FlowKind, val count: Int) : Row

/** Заголовок раздела. */
internal data class SectionRow(val text: String) : Row

/** Пометка «цикл» или «обрезано». */
internal data class NoteRow(val text: String) : Row

/**
 * Строит дерево в стиле Dataflow у IntelliJ. Каждое ребро — строка с местом перехода;
 * если следующий узел объявлен на той же строке (`c := s.cfg`), строки сливаются в одну.
 * Фильтр по типам действует только на первый уровень — прямые использования выбранного элемента.
 */
internal class TraceTreeBuilder(
    private val graph: FlowGraph,
    private val kinds: Set<FlowKind>,
    private val groupByKind: Boolean,
    private val cycleText: String,
    private val truncatedText: String,
    private val maxRows: Int = 5000,
) {
    private var budget = maxRows

    /** Корень трассировки: выбранный элемент и его использования. */
    fun buildTrace(): DefaultMutableTreeNode {
        val start = graph.start
        val root = DefaultMutableTreeNode(CodeRow(start.code, null, start))
        val firstLevel = graph.next(start).filter { it.kind in kinds }
        val onPath = hashSetOf(start)
        if (groupByKind) {
            firstLevel.groupBy { it.kind }.toSortedMap().forEach { (kind, edges) ->
                val group = DefaultMutableTreeNode(KindRow(kind, edges.size))
                edges.forEach { addEdge(group, start.code, it, onPath, direct = true) }
                root.add(group)
            }
        } else {
            firstLevel.forEach { addEdge(root, start.code, it, onPath, direct = true) }
        }
        return root
    }

    /** Конечные точки и цепочки к ним в порядке движения данных. */
    fun buildTerminals(title: (Int) -> String, visible: Set<FlowNode>): DefaultMutableTreeNode {
        val terminals = graph.terminals(visible)
        val section = DefaultMutableTreeNode(SectionRow(title(terminals.size)))
        for (terminal in terminals) {
            val row = DefaultMutableTreeNode(CodeRow(terminal.code, null, null))
            val path = graph.pathFromStart(terminal, visible)
            // Строки по ходу данных: узел, место перехода, следующий узел…; одна строка кода — одна строка дерева.
            val lines = ArrayList<Pair<CodeLine, FlowEdge?>>()
            lines += graph.start.code to null
            path.forEach { e ->
                lines += e.site to e
                lines += graph.ahead(e).code to null
            }
            val ordered = if (graph.direction == Direction.BACKWARD) lines.asReversed() else lines
            // Строка самой конечной точки уже показана строкой выше.
            ordered.fold(terminal.code as CodeLine?) { previous, (code, edge) ->
                if (previous != null && previous.sameLine(code) || code.sameLine(terminal.code)) previous
                else code.also { row.add(DefaultMutableTreeNode(CodeRow(it, edge, null))) }
            }
            section.add(row)
        }
        return section
    }

    /**
     * Строки для ребра под строкой [parent]. Место перехода на той же строке, что и [parent], отдельно не показывается;
     * следующий узел на той же строке — тоже: его продолжения идут прямо под [parent].
     * Прямое использование ([direct]) всегда получает свою строку, даже на строке объявления: `x := f()`.
     */
    private fun addEdge(
        parent: DefaultMutableTreeNode, parentCode: CodeLine, edge: FlowEdge, onPath: MutableSet<FlowNode>, direct: Boolean = false,
    ) {
        budget--
        val next = graph.ahead(edge)
        when {
            direct && edge.site.sameLine(parentCode) -> {
                val row = DefaultMutableTreeNode(CodeRow(edge.site, edge, next))
                parent.add(row)
                if (next.code.sameLine(edge.site)) continueFrom(row, edge.site, next, onPath)
                else row.add(DefaultMutableTreeNode(CodeRow(next.code, null, next)).also { continueFrom(it, next.code, next, onPath) })
            }
            edge.site.sameLine(parentCode) && next.code.sameLine(parentCode) -> continueFrom(parent, parentCode, next, onPath)
            edge.site.sameLine(parentCode) || edge.site.sameLine(next.code) -> {
                val code = if (edge.site.sameLine(parentCode)) next.code else edge.site
                val row = DefaultMutableTreeNode(CodeRow(code, edge, next))
                parent.add(row)
                continueFrom(row, code, next, onPath)
            }
            else -> {
                val row = DefaultMutableTreeNode(CodeRow(edge.site, edge, null))
                val nodeRow = DefaultMutableTreeNode(CodeRow(next.code, null, next))
                row.add(nodeRow)
                parent.add(row)
                continueFrom(nodeRow, next.code, next, onPath)
            }
        }
    }

    private fun continueFrom(row: DefaultMutableTreeNode, rowCode: CodeLine, node: FlowNode, onPath: MutableSet<FlowNode>) {
        val edges = graph.next(node)
        if (edges.isEmpty()) return
        if (!onPath.add(node)) {
            row.add(DefaultMutableTreeNode(NoteRow(cycleText)))
            return
        }
        for (e in edges) {
            if (budget <= 0) {
                row.add(DefaultMutableTreeNode(NoteRow(truncatedText)))
                break
            }
            addEdge(row, rowCode, e, onPath)
        }
        onPath.remove(node)
    }
}
