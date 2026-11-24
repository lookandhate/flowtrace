package io.github.lookandhate.flowtrace.ui

import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.lookandhate.flowtrace.FlowTraceBundle.message
import io.github.lookandhate.flowtrace.model.Direction
import io.github.lookandhate.flowtrace.model.FlowEdge
import io.github.lookandhate.flowtrace.model.FlowGraph
import io.github.lookandhate.flowtrace.model.FlowNode
import java.awt.BasicStroke
import java.awt.Dimension
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.CubicCurve2D
import java.awt.geom.Path2D
import javax.swing.JPanel
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Послойная картинка графа. Данные всегда текут сверху вниз:
 * для «откуда пришло» источники наверху, переменная внизу; для «куда уходит» — наоборот.
 */
internal class FlowGraphPanel(
    private val graph: FlowGraph,
    private val onSelect: (FlowNode) -> Unit,
    private val onOpen: (FlowNode) -> Unit,
) : JPanel() {
    private val bounds = LinkedHashMap<FlowNode, Rectangle>()
    private val layerOf = HashMap<FlowNode, Int>()
    private val titleFont = JBFont.label().asBold()
    private val subFont = JBFont.small()
    private var selected: FlowNode? = null

    init {
        background = UIUtil.getPanelBackground()
        toolTipText = ""
        layoutGraph()
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val n = nodeAt(e.x, e.y) ?: return
                select(n)
                if (e.clickCount >= 2) onOpen(n) else onSelect(n)
            }
        })
    }

    fun select(n: FlowNode) {
        selected = n
        bounds[n]?.let { r ->
            val m = MARGIN.get()
            scrollRectToVisible(Rectangle(r.x - m, r.y - m, r.width + 2 * m, r.height + 2 * m))
        }
        repaint()
    }

    private fun nodeAt(x: Int, y: Int): FlowNode? = bounds.entries.firstOrNull { it.value.contains(x, y) }?.key

    override fun getToolTipText(event: MouseEvent): String? {
        val n = nodeAt(event.x, event.y) ?: return null
        fun esc(s: String) = StringUtil.escapeXmlEntities(s)
        return buildString {
            append("<html><b>${esc(n.title)}</b><br>${esc(n.context)} · ${esc(n.location)}")
            append("<br><code>${esc(n.snippet)}</code>")
            n.incoming.forEach { append("<br>← ${esc(it.label)}: ${esc(it.from.title)}") }
            if (n.isTruncated) append("<br><i>${message("graph.tooltip.truncated")}</i>")
            append("<br><i>${message("graph.tooltip.open")}</i></html>")
        }
    }

    // ---------------------------------------------------------------- раскладка

    private fun layoutGraph() {
        val dist = graph.layers()
        val maxDist = dist.values.maxOrNull() ?: 0
        val backward = graph.direction == Direction.BACKWARD
        for (n in graph.nodes) {
            val d = dist[n] ?: maxDist
            layerOf[n] = if (backward) maxDist - d else d
        }
        val layers = graph.nodes.groupBy { layerOf.getValue(it) }
        val layerCount = (layers.keys.maxOrNull() ?: 0) + 1

        val tfm = getFontMetrics(titleFont)
        val sfm = getFontMetrics(subFont)
        val pad = PAD.get()
        val gap = H_GAP.get()
        val nodeHeight = tfm.height + sfm.height + 2 * pad
        val widths = graph.nodes.associateWith {
            minOf(maxOf(tfm.stringWidth(it.title), sfm.stringWidth(it.subtitle)) + 2 * pad + JBUI.scale(4), MAX_WIDTH.get())
        }

        // Порядок внутри слоя: барицентр соседей из уже разложенных слоёв — меньше пересечений.
        val position = HashMap<FlowNode, Double>()
        val ordered = (0 until layerCount).map { layer ->
            val list = layers[layer].orEmpty().let { if (layer > 0) it.sortedBy { n -> barycenter(n, position) } else it }
            list.forEachIndexed { i, n -> position[n] = i.toDouble() }
            list
        }
        val rowWidth = { list: List<FlowNode> -> list.sumOf { widths.getValue(it) + gap } }
        val totalWidth = ordered.maxOfOrNull(rowWidth) ?: 0

        var y = MARGIN.get()
        for (list in ordered) {
            var x = MARGIN.get() + (totalWidth - rowWidth(list)) / 2
            for (n in list) {
                val w = widths.getValue(n)
                bounds[n] = Rectangle(x, y, w, nodeHeight)
                position[n] = x + w / 2.0
                x += w + gap
            }
            y += nodeHeight + V_GAP.get()
        }
        preferredSize = Dimension(totalWidth + 2 * MARGIN.get() + JBUI.scale(80), y + MARGIN.get())
    }

    private fun barycenter(n: FlowNode, position: Map<FlowNode, Double>): Double {
        val neighbours = (n.incoming.map { it.from } + n.outgoing.map { it.to }).mapNotNull { position[it] }
        return if (neighbours.isEmpty()) Double.MAX_VALUE else neighbours.average()
    }

    private val FlowNode.subtitle get() = "$context · $location"

    // ---------------------------------------------------------------- рисование

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            graph.nodes.forEach { n -> n.outgoing.forEach { paintEdge(g, it) } }
            bounds.forEach { (n, r) -> paintNode(g, n, r) }
        } finally {
            g.dispose()
        }
    }

    private fun paintEdge(g: Graphics2D, e: FlowEdge) {
        val a = bounds[e.from] ?: return
        val b = bounds[e.to] ?: return
        val highlighted = selected != null && (e.from == selected || e.to == selected)
        g.color = if (highlighted) NodeStyle.SELECTED else NodeStyle.EDGE
        g.stroke = BasicStroke(JBUIScale.scale(if (highlighted) 2f else 1f))
        val curve = if (layerOf.getValue(e.from) < layerOf.getValue(e.to)) {
            val x1 = a.centerX
            val y1 = a.maxY
            val x2 = b.centerX
            val y2 = b.minY
            val dy = (y2 - y1) / 2
            CubicCurve2D.Double(x1, y1, x1, y1 + dy, x2, y2 - dy, x2, y2)
        } else {
            // Ребро вверх или вбок (цикл): дуга справа.
            val bulge = JBUIScale.scale(60f).toDouble()
            CubicCurve2D.Double(a.maxX, a.centerY, a.maxX + bulge, a.centerY, b.maxX + bulge, b.centerY, b.maxX, b.centerY)
        }
        g.draw(curve)
        arrow(g, curve.ctrlX2, curve.ctrlY2, curve.x2, curve.y2)
    }

    private fun arrow(g: Graphics2D, fromX: Double, fromY: Double, x: Double, y: Double) {
        val angle = atan2(y - fromY, x - fromX)
        val len = JBUIScale.scale(8f).toDouble()
        val spread = Math.PI / 7
        g.fill(Path2D.Double().apply {
            moveTo(x, y)
            lineTo(x - len * cos(angle - spread), y - len * sin(angle - spread))
            lineTo(x - len * cos(angle + spread), y - len * sin(angle + spread))
            closePath()
        })
    }

    private fun paintNode(g: Graphics2D, n: FlowNode, r: Rectangle) {
        val arc = JBUI.scale(10)
        g.color = NodeStyle.fill(n)
        g.fillRoundRect(r.x, r.y, r.width, r.height, arc, arc)
        val isSelected = n == selected
        val width = JBUIScale.scale(if (isSelected) 2.5f else if (n.isStart) 2f else 1f)
        val dash = if (n.isTruncated) floatArrayOf(JBUIScale.scale(4f), JBUIScale.scale(3f)) else null
        g.color = if (isSelected) NodeStyle.SELECTED else NodeStyle.BORDER
        g.stroke = BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, dash, 0f)
        g.drawRoundRect(r.x, r.y, r.width, r.height, arc, arc)

        val pad = PAD.get()
        val textWidth = r.width - 2 * pad
        g.font = titleFont
        val tfm = g.fontMetrics
        g.color = UIUtil.getLabelForeground()
        g.drawString(fit(n.title, tfm, textWidth), r.x + pad, r.y + pad + tfm.ascent)
        g.font = subFont
        val sfm = g.fontMetrics
        g.color = UIUtil.getContextHelpForeground()
        g.drawString(fit(n.subtitle, sfm, textWidth), r.x + pad, r.y + pad + tfm.height + sfm.ascent)
    }

    private fun fit(s: String, fm: FontMetrics, width: Int): String {
        if (fm.stringWidth(s) <= width) return s
        var end = s.length
        while (end > 0 && fm.stringWidth(s.substring(0, end) + "…") > width) end--
        return s.substring(0, end) + "…"
    }

    private companion object {
        val PAD = JBUI.uiIntValue("FlowTrace.Graph.padding", 8)
        val H_GAP = JBUI.uiIntValue("FlowTrace.Graph.horizontalGap", 24)
        val V_GAP = JBUI.uiIntValue("FlowTrace.Graph.verticalGap", 56)
        val MAX_WIDTH = JBUI.uiIntValue("FlowTrace.Graph.maxNodeWidth", 360)
        val MARGIN = JBUI.uiIntValue("FlowTrace.Graph.margin", 16)
    }
}
