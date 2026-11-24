package io.github.lookandhate.flowtrace.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.JBSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import io.github.lookandhate.flowtrace.FlowTraceBundle.message
import io.github.lookandhate.flowtrace.model.Direction
import io.github.lookandhate.flowtrace.model.FlowGraph
import io.github.lookandhate.flowtrace.model.FlowNode
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/** Результат одного анализа: дерево слева, граф справа. */
internal class FlowResultPanel(private val project: Project, private val graph: FlowGraph) :
    JPanel(BorderLayout()), Disposable {

    /** Строка дерева: узел графа, подпись ребра, по которому в него пришли, и номер в цепочке. */
    private data class Item(val node: FlowNode, val via: String? = null, val number: Int = 0)

    private val backward = graph.direction == Direction.BACKWARD
    private var treeBudget = MAX_TREE_NODES
    internal val tree: Tree
    internal val graphPanel: FlowGraphPanel

    init {
        val terminals = graph.terminals()
        val summary = message(
            if (backward) "summary.backward" else "summary.forward",
            graph.start.title, terminals.size.toString(), graph.nodes.size.toString(),
        ) + if (graph.truncated) message("summary.truncated") else ""
        add(JBLabel(summary).apply { border = JBUI.Borders.empty(4, 8) }, BorderLayout.NORTH)

        graphPanel = FlowGraphPanel(
            graph,
            onSelect = { it.navigate(project, focus = false) },
            onOpen = { it.navigate(project, focus = true) },
        )
        tree = Tree(DefaultTreeModel(buildTree(terminals))).apply {
            isRootVisible = false
            showsRootHandles = true
            cellRenderer = Renderer()
            addTreeSelectionListener { nodeOf(lastSelectedPathComponent)?.let(graphPanel::select) }
            addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    if (e.keyCode == KeyEvent.VK_ENTER) nodeOf(lastSelectedPathComponent)?.navigate(project, focus = true)
                }
            })
        }
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val node = tree.getPathForLocation(event.x, event.y)?.lastPathComponent?.let(::nodeOf) ?: return false
                node.navigate(project, focus = true)
                return true
            }
        }.installOn(tree)
        TreeUtil.expand(tree, 1)

        add(JBSplitter(false, 0.38f).apply {
            firstComponent = ScrollPaneFactory.createScrollPane(tree, true)
            secondComponent = ScrollPaneFactory.createScrollPane(graphPanel, true)
        }, BorderLayout.CENTER)
    }

    private fun buildTree(terminals: List<FlowNode>): DefaultMutableTreeNode {
        val root = DefaultMutableTreeNode()

        val ends = DefaultMutableTreeNode(
            message(if (backward) "tree.terminals.backward" else "tree.terminals.forward", terminals.size.toString())
        )
        root.add(ends)
        for (terminal in terminals) {
            val terminalNode = DefaultMutableTreeNode(Item(terminal))
            ends.add(terminalNode)
            // Цепочка в порядке движения данных: от источника к переменной или от переменной к месту использования.
            val path = graph.pathFromStart(terminal).let { if (backward) it.asReversed() else it }
            val first = if (backward) terminal else graph.start
            val chain = listOf(Item(first, number = 1)) +
                path.mapIndexed { i, e -> Item(e.to, e.label, i + 2) }
            chain.forEach { terminalNode.add(DefaultMutableTreeNode(it)) }
        }

        val full = DefaultMutableTreeNode(message(if (backward) "tree.full.backward" else "tree.full.forward"))
        root.add(full)
        val startNode = DefaultMutableTreeNode(Item(graph.start))
        full.add(startNode)
        addChildren(startNode, graph.start, hashSetOf(graph.start))
        return root
    }

    private fun addChildren(parent: DefaultMutableTreeNode, node: FlowNode, onPath: MutableSet<FlowNode>) {
        for (edge in graph.next(node)) {
            if (--treeBudget < 0) {
                parent.add(DefaultMutableTreeNode(message("tree.truncated")))
                return
            }
            val child = graph.ahead(edge)
            val childNode = DefaultMutableTreeNode(Item(child, edge.label))
            parent.add(childNode)
            if (onPath.add(child)) {
                addChildren(childNode, child, onPath)
                onPath.remove(child)
            } else {
                childNode.add(DefaultMutableTreeNode(message("tree.cycle")))
            }
        }
    }

    override fun dispose() = Unit

    private class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean,
        ) {
            val item = (value as? DefaultMutableTreeNode)?.userObject as? Item
            if (item == null) {
                append((value as? DefaultMutableTreeNode)?.userObject?.toString() ?: "", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                return
            }
            val n = item.node
            icon = NodeStyle.icon(n)
            if (item.number > 0) append("${item.number}. ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            item.via?.let { append("[$it] ", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES) }
            val emphasized = n.isStart || n.kind == FlowNode.Kind.ORIGIN || n.kind == FlowNode.Kind.EXTERNAL
            append(n.title, if (emphasized) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
            append("  ${n.context} · ${n.location}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            if (n.isTruncated) append(message("node.truncated"), SimpleTextAttributes.ERROR_ATTRIBUTES)
            toolTipText = n.snippet
        }
    }

    private companion object {
        const val MAX_TREE_NODES = 3000

        fun nodeOf(treeNode: Any?): FlowNode? = ((treeNode as? DefaultMutableTreeNode)?.userObject as? Item)?.node
    }
}
