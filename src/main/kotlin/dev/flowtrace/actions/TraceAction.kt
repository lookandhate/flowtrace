package dev.flowtrace.actions

import com.goide.psi.GoFile
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import dev.flowtrace.analysis.GoFlowAnalyzer
import dev.flowtrace.model.Direction
import dev.flowtrace.services.FlowTraceService

/**
 * Пункт контекстного меню редактора. Не DumbAware: поиск цели резолвит ссылки,
 * поэтому во время индексации действие выключено.
 */
sealed class TraceAction(private val direction: Direction) : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.PSI_FILE)
        val editor = e.getData(CommonDataKeys.EDITOR)
        val visible = e.project != null && file is GoFile && editor != null
        e.presentation.isVisible = visible
        e.presentation.isEnabled = visible && GoFlowAnalyzer.findTarget(file, editor.caretModel.offset) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.PSI_FILE) ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        FlowTraceService.getInstance(project).trace(file, editor, editor.caretModel.offset, direction)
    }
}

/** ПКМ → «Откуда пришло значение»: самые верхние источники и цепочка вызовов до переменной. */
class TraceOriginsAction : TraceAction(Direction.BACKWARD)

/** ПКМ → «Куда уходит значение»: через какие функции и конструкторы проходит и где используется. */
class TraceSinksAction : TraceAction(Direction.FORWARD)
