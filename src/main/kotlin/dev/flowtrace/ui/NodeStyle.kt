package dev.flowtrace.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import dev.flowtrace.model.FlowNode
import dev.flowtrace.model.FlowNode.Kind
import java.awt.Color
import javax.swing.Icon

internal object NodeStyle {
    val START = JBColor(Color(0xD6E4FF), Color(0x2E436E))
    val ORIGIN = JBColor(Color(0xDDF3DC), Color(0x2F4F2F))
    val SINK = JBColor(Color(0xFFE9CC), Color(0x5A4424))
    val EXTERNAL = JBColor(Color(0xEDE3F7), Color(0x46385A))
    val PLAIN = JBColor(Color(0xF5F5F5), Color(0x3C3F41))
    val BORDER = JBColor(Color(0x9AA0A6), Color(0x6E7276))
    val SELECTED = JBColor(Color(0x2F6FEB), Color(0x589DF6))
    val EDGE = JBColor(Color(0x80868B), Color(0x8C9094))

    fun fill(n: FlowNode): Color = when {
        n.isStart -> START
        n.kind == Kind.ORIGIN -> ORIGIN
        n.kind == Kind.SINK -> SINK
        n.kind == Kind.EXTERNAL -> EXTERNAL
        else -> PLAIN
    }

    fun icon(n: FlowNode): Icon = when (n.kind) {
        Kind.VARIABLE -> AllIcons.Nodes.Variable
        Kind.PARAMETER, Kind.RECEIVER -> AllIcons.Nodes.Parameter
        Kind.FIELD -> AllIcons.Nodes.Field
        Kind.RESULT, Kind.CALL -> AllIcons.Nodes.Function
        Kind.ORIGIN -> AllIcons.Nodes.Class
        Kind.EXTERNAL -> AllIcons.Nodes.PpLib
        Kind.SINK -> AllIcons.Nodes.Method
    }
}

/** Открывает код узла. Узел хранит smart pointer, поэтому переход работает и после правок файла. */
internal fun FlowNode.navigate(project: Project, focus: Boolean) {
    val ptr = pointer ?: return
    val file = ptr.virtualFile ?: return
    val element = ptr.element ?: return
    OpenFileDescriptor(project, file, element.textOffset).navigate(focus)
}
