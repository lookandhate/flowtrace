package io.github.lookandhate.flowtrace.ui

import com.intellij.ide.CommonActionsManager
import com.intellij.ide.DefaultTreeExpander
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.icons.AllIcons
import com.intellij.ui.AutoScrollToSourceHandler
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
import io.github.lookandhate.flowtrace.model.FlowKind
import io.github.lookandhate.flowtrace.model.FlowNode
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * Результат одного анализа: дерево в стиле Dataflow слева, граф справа, панель инструментов сбоку.
 * Прямые использования выбранного элемента группируются и фильтруются по типам.
 */
internal class FlowResultPanel(private val project: Project, private val graph: FlowGraph) :
    JPanel(BorderLayout()), Disposable {

    private val backward = graph.direction == Direction.BACKWARD
    private val firstLevelKinds = graph.firstLevelKinds()

    /** Показываемые типы прямых использований. */
    internal var kinds: Set<FlowKind> = firstLevelKinds.keys
        set(value) {
            field = value
            rebuild()
        }

    internal var groupByKind: Boolean = true
        set(value) {
            field = value
            rebuild()
        }

    private var showGraph: Boolean = true
        set(value) {
            field = value
            splitter.secondComponent = if (value) graphScroll else null
        }

    private val header = JBLabel().apply { border = JBUI.Borders.empty(4, 8) }
    private val codeRenderer = CodeLineRenderer(project)
    internal val tree = Tree(DefaultTreeModel(DefaultMutableTreeNode())).apply {
        isRootVisible = false
        showsRootHandles = true
        cellRenderer = Renderer()
    }
    internal var graphPanel: FlowGraphPanel = createGraphPanel(graph.nodes.toSet())
        private set
    private val graphScroll = ScrollPaneFactory.createScrollPane(graphPanel, true)
    private val splitter = JBSplitter(false, 0.55f).apply {
        firstComponent = ScrollPaneFactory.createScrollPane(tree, true)
        secondComponent = graphScroll
    }

    init {
        tree.addTreeSelectionListener {
            rowOf(tree.lastSelectedPathComponent)?.let { row -> nodeFor(row)?.let(graphPanel::select) }
        }
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) codeOf(tree.lastSelectedPathComponent)?.navigate(project, focus = true)
            }
        })
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val path = tree.getPathForLocation(event.x, event.y) ?: return false
                val code = codeOf(path.lastPathComponent) ?: return false
                code.navigate(project, focus = true)
                return true
            }
        }.installOn(tree)

        val autoScroll = object : AutoScrollToSourceHandler() {
            override fun isAutoScrollMode() = PropertiesComponent.getInstance(project).getBoolean(AUTOSCROLL_KEY, true)
            override fun setAutoScrollMode(state: Boolean) =
                PropertiesComponent.getInstance(project).setValue(AUTOSCROLL_KEY, state, true)

            override fun scrollToSource(tree: java.awt.Component) {
                codeOf((tree as JTree).lastSelectedPathComponent)?.navigate(project, focus = false)
            }
        }
        autoScroll.install(tree)

        val toolbar = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, createActions(autoScroll), false)
        toolbar.targetComponent = tree

        add(header, BorderLayout.NORTH)
        add(toolbar.component, BorderLayout.WEST)
        add(splitter, BorderLayout.CENTER)
        rebuild()
    }

    private fun createActions(autoScroll: AutoScrollToSourceHandler): DefaultActionGroup {
        val expander = DefaultTreeExpander(tree)
        val actions = CommonActionsManager.getInstance()
        return DefaultActionGroup().apply {
            add(GroupByKindAction())
            add(KindFilterGroup())
            addSeparator()
            add(actions.createExpandAllAction(expander, tree))
            add(actions.createCollapseAllAction(expander, tree))
            add(autoScroll.createToggleAction())
            addSeparator()
            add(ShowGraphAction())
        }
    }

    private fun rebuild() {
        val visible = graph.reachableVia(kinds)
        val builder = TraceTreeBuilder(graph, kinds, groupByKind, message("tree.cycle"), message("tree.truncated"))
        val root = DefaultMutableTreeNode()
        val trace = builder.buildTrace()
        root.add(trace)
        root.add(builder.buildTerminals({ n ->
            message(if (backward) "tree.terminals.backward" else "tree.terminals.forward", n.toString())
        }, visible))
        tree.model = DefaultTreeModel(root)
        TreeUtil.expand(tree, if (groupByKind) 3 else 2)

        val shown = graph.next(graph.start).count { it.kind in kinds }
        header.text = message(
            if (backward) "summary.backward" else "summary.forward",
            graph.start.title, shown.toString(), graph.terminals(visible).size.toString(),
        ) + if (graph.truncated) message("summary.truncated") else ""

        graphPanel = createGraphPanel(visible)
        graphScroll.setViewportView(graphPanel)
    }

    private fun createGraphPanel(visible: Set<FlowNode>) = FlowGraphPanel(
        graph, visible,
        onSelect = { it.navigate(project, focus = false) },
        onOpen = { it.navigate(project, focus = true) },
    )

    /** Узел графа для строки: для строки-места — куда ведёт ребро. */
    private fun nodeFor(row: Row): FlowNode? = when (row) {
        is CodeRow -> row.node ?: row.edge?.let(graph::ahead)
        else -> null
    }

    override fun dispose() = Unit

    // ---------------------------------------------------------------- действия

    private inner class GroupByKindAction : DumbAwareToggleAction(
        message("action.group.by.kind"), null, AllIcons.Actions.GroupBy,
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent) = groupByKind
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            groupByKind = state
        }
    }

    private inner class ShowGraphAction : DumbAwareToggleAction(
        message("action.show.graph"), null, AllIcons.Actions.PreviewDetails,
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent) = showGraph
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            showGraph = state
        }
    }

    /** Выпадающий список типов прямых использований с числом каждого. */
    private inner class KindFilterGroup : DefaultActionGroup(message("action.filter.kinds"), true) {
        init {
            templatePresentation.icon = AllIcons.General.Filter
            add(object : DumbAwareAction(message("action.filter.all")) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun actionPerformed(e: AnActionEvent) {
                    kinds = firstLevelKinds.keys
                }
            })
            addSeparator()
            firstLevelKinds.forEach { (kind, count) -> add(KindToggle(kind, count)) }
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private inner class KindToggle(private val kind: FlowKind, count: Int) : DumbAwareToggleAction(
        message("kind.with.count", message(kind.messageKey), count.toString()), null, NodeStyle.icon(kind),
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent) = kind in kinds
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            kinds = if (state) kinds + kind else kinds - kind
        }
    }

    // ---------------------------------------------------------------- отрисовка строк

    private inner class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean,
        ) {
            when (val r = rowOf(value)) {
                is CodeRow -> renderCode(r, selected)
                is KindRow -> {
                    icon = NodeStyle.icon(r.kind)
                    append(message(r.kind.messageKey), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append("  ${r.count}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is SectionRow -> append(r.text, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                is NoteRow -> append(r.text, SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                null -> Unit
            }
        }

        private fun renderCode(r: CodeRow, selected: Boolean) {
            append("${r.code.line}  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            codeRenderer.render(this, r.code, selected)
            append("  " + message("row.in", r.code.context), SimpleTextAttributes.GRAYED_ATTRIBUTES)
            val node = r.node
            if (node != null && node.isTruncated) append(message("node.truncated"), SimpleTextAttributes.ERROR_ATTRIBUTES)
            toolTipText = listOfNotNull(r.edge?.label, node?.title, r.code.location).joinToString(" · ")
        }
    }

    private companion object {
        const val TOOLBAR_PLACE = "FlowTrace.Toolbar"
        const val AUTOSCROLL_KEY = "flowtrace.autoscroll"

        fun rowOf(treeNode: Any?): Row? = (treeNode as? DefaultMutableTreeNode)?.userObject as? Row

        fun codeOf(treeNode: Any?) = (rowOf(treeNode) as? CodeRow)?.code
    }
}
