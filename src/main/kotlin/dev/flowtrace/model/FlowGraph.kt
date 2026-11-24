package dev.flowtrace.model

import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPsiElementPointer
import org.jetbrains.annotations.Nls

enum class Direction {
    /** Откуда пришло значение: против потока к самым верхним источникам. */
    BACKWARD,

    /** Куда уходит значение: по потоку к конечным использованиям. */
    FORWARD,
}

/** Узел графа потока данных. Все строки посчитаны в read action, после анализа PSI не нужен. */
class FlowNode internal constructor(
    val id: Int,
    val kind: Kind,
    @Nls val title: String,
    /** Строка кода, где находится элемент. */
    val snippet: String,
    /** Функция или метод, внутри которого находится элемент. */
    @Nls val context: String,
    /** Файл и строка: «server.go:42». */
    val location: String,
    val pointer: SmartPsiElementPointer<PsiElement>?,
) {
    enum class Kind { VARIABLE, PARAMETER, RECEIVER, FIELD, RESULT, CALL, ORIGIN, EXTERNAL, SINK }

    internal val incomingEdges = mutableListOf<FlowEdge>()
    internal val outgoingEdges = mutableListOf<FlowEdge>()

    val incoming: List<FlowEdge> get() = incomingEdges
    val outgoing: List<FlowEdge> get() = outgoingEdges

    var isStart: Boolean = false
        internal set

    /** Дальше не исследовался: кончился бюджет узлов или глубины. */
    var isTruncated: Boolean = false
        internal set

    override fun toString(): String = "$title [$context, $location]"
}

/** Ребро направлено по потоку данных: значение переходит из [from] в [to]. */
data class FlowEdge(val from: FlowNode, val to: FlowNode, @Nls val label: String)

class FlowGraph internal constructor(
    val direction: Direction,
    val start: FlowNode,
    val nodes: List<FlowNode>,
    /** Граф неполный: сработал лимит узлов или глубины. */
    val truncated: Boolean,
) {
    /** Рёбра в направлении анализа: для BACKWARD — откуда пришло, для FORWARD — куда ушло. */
    fun next(node: FlowNode): List<FlowEdge> =
        if (direction == Direction.BACKWARD) node.incoming else node.outgoing

    /** Конец ребра дальше по направлению анализа. */
    fun ahead(edge: FlowEdge): FlowNode = if (direction == Direction.BACKWARD) edge.from else edge.to

    /** Конец ребра ближе к стартовому узлу. */
    fun behind(edge: FlowEdge): FlowNode = if (direction == Direction.BACKWARD) edge.to else edge.from

    /**
     * Конечные точки анализа: для BACKWARD — самые верхние источники,
     * для FORWARD — места, дальше которых значение не уходит.
     */
    fun terminals(): List<FlowNode> = nodes.filter { it != start && next(it).isEmpty() }

    /** Кратчайшая цепочка рёбер от старта до [target] в направлении анализа. */
    fun pathFromStart(target: FlowNode): List<FlowEdge> {
        val via = bfs(stopAt = target)
        if (target !in via) return emptyList()
        return generateSequence(via[target]) { via[behind(it)] }.toList().asReversed()
    }

    /**
     * Слой узла для рисования: длиннейший путь от старта по направлению анализа.
     * Рёбра, замыкающие цикл, не учитываются, поэтому каждое прочее ребро идёт строго к следующему слою.
     */
    fun layers(): Map<FlowNode, Int> {
        val order = ArrayList<FlowNode>()
        val state = HashMap<FlowNode, Boolean>() // false — в стеке обхода, true — обработан
        val back = HashSet<FlowEdge>()
        fun dfs(n: FlowNode) {
            state[n] = false
            for (e in next(n)) {
                val m = ahead(e)
                when (state[m]) {
                    null -> dfs(m)
                    false -> back += e
                    true -> Unit
                }
            }
            state[n] = true
            order += n
        }
        dfs(start)
        nodes.filter { it !in state }.forEach(::dfs)
        val layer = HashMap<FlowNode, Int>()
        for (n in order.asReversed()) {
            val l = layer.getOrPut(n) { 0 }
            for (e in next(n)) if (e !in back) layer[ahead(e)] = maxOf(layer[ahead(e)] ?: 0, l + 1)
        }
        return layer
    }

    private fun bfs(stopAt: FlowNode?): Map<FlowNode, FlowEdge?> {
        val via = LinkedHashMap<FlowNode, FlowEdge?>()
        val queue = ArrayDeque<FlowNode>()
        via[start] = null
        queue.add(start)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (cur == stopAt) break
            for (e in next(cur)) {
                val n = ahead(e)
                if (n !in via) {
                    via[n] = e
                    queue.add(n)
                }
            }
        }
        return via
    }
}
