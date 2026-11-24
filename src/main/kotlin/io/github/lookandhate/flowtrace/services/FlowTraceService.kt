package io.github.lookandhate.flowtrace.services

import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.psi.PsiFile
import io.github.lookandhate.flowtrace.FlowTraceBundle.message
import io.github.lookandhate.flowtrace.analysis.GoFlowAnalyzer
import io.github.lookandhate.flowtrace.model.Direction
import io.github.lookandhate.flowtrace.ui.FlowToolWindowFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Запускает анализ в фоне. Scope сервиса отменяется при закрытии проекта или выгрузке плагина,
 * read action — при записи в PSI: анализ перезапускается сам.
 */
@Service(Service.Level.PROJECT)
class FlowTraceService(private val project: Project, private val scope: CoroutineScope) {

    fun trace(file: PsiFile, editor: Editor, offset: Int, direction: Direction) {
        scope.launch {
            val title = message(if (direction == Direction.BACKWARD) "progress.backward" else "progress.forward")
            val graph = withBackgroundProgress(project, title) {
                smartReadAction(project) {
                    GoFlowAnalyzer.findTarget(file, offset)?.let { GoFlowAnalyzer(project, direction).analyze(it) }
                }
            }
            withContext(Dispatchers.EDT) {
                if (graph == null) {
                    if (!editor.isDisposed) HintManager.getInstance().showErrorHint(editor, message("hint.no.target"))
                } else {
                    FlowToolWindowFactory.show(project, graph)
                }
            }
        }
    }

    companion object {
        fun getInstance(project: Project): FlowTraceService = project.service()
    }
}
