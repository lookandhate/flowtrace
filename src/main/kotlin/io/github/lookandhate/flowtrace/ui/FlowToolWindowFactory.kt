package io.github.lookandhate.flowtrace.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.util.concurrency.annotations.RequiresEdt
import io.github.lookandhate.flowtrace.FlowTraceBundle.message
import io.github.lookandhate.flowtrace.model.Direction
import io.github.lookandhate.flowtrace.model.FlowGraph
import javax.swing.SwingConstants

/** Окно Flow Trace: по вкладке на каждый запуск анализа. */
class FlowToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val hint = JBLabel(message("toolwindow.hint"), SwingConstants.CENTER)
        val content = ContentFactory.getInstance().createContent(hint, message("toolwindow.hint.tab"), false)
        toolWindow.contentManager.addContent(content)
    }

    companion object {
        const val ID = "FlowTrace"

        @RequiresEdt
        fun show(project: Project, graph: FlowGraph) {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ID) ?: return
            val contentManager = toolWindow.contentManager
            contentManager.contents.filter { it.component !is FlowResultPanel }
                .forEach { contentManager.removeContent(it, true) }

            val panel = FlowResultPanel(project, graph)
            val arrow = if (graph.direction == Direction.BACKWARD) "← " else "→ "
            val content = ContentFactory.getInstance().createContent(panel, arrow + graph.start.title, false).apply {
                isCloseable = true
                setDisposer(panel)
            }
            contentManager.addContent(content)
            contentManager.setSelectedContent(content)
            toolWindow.activate(null)
        }
    }
}
