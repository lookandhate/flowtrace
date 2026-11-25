package io.github.lookandhate.flowtrace.model

import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPsiElementPointer
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

enum class Direction {
    /** Откуда пришло значение: против потока к самым верхним источникам. */
    BACKWARD,

    /** Куда уходит значение: по потоку к конечным использованиям. */
    FORWARD,
}

/** Тип шага потока: как значение попало в узел или как используется. Порядок — порядок групп в дереве. */
enum class FlowKind(@PropertyKey(resourceBundle = "messages.FlowTraceBundle") val messageKey: String) {
    /** `x = y`, `x := y`, `x += y`. */
    ASSIGNMENT("kind.assignment"),

    /** Аргумент вызова: вниз по стеку (FORWARD) или из вызывающего кода (BACKWARD). */
    ARGUMENT("kind.argument"),

    /** `return`: наверх к вызывающему (FORWARD) или из вызванной функции (BACKWARD). */
    RETURN("kind.return"),

    /** Вызов метода на значении: `v.Method()`. */
    METHOD_CALL("kind.method.call"),

    /** Поля структур и литералы: `T{f: v}`, `s.f = v`, `v.f`. */
    FIELD("kind.field"),

    /** Перезапись самой переменной: `v = …`, повторное `v, err := …`, `v++`. Только FORWARD, дальше не прослеживается. */
    WRITE("kind.write"),

    /** Объявление без значения, нет вызовов, функция передана как значение. */
    DECLARATION("kind.declaration"),

    /** Условия, выражения, встроенные функции, каналы и прочее. */
    OTHER("kind.other"),
}

/**
 * Строка кода, на которой находится элемент: как в дереве Dataflow у IntelliJ.
 * Посчитана в read action, после анализа PSI не нужен.
 */
data class CodeLine(
    val pointer: SmartPsiElementPointer<PsiElement>?,
    val filePath: String,
    val fileName: String,
    val fileType: FileType?,
    /** Номер строки с единицы. */
    val line: Int,
    /** Текст строки без отступа. */
    val text: String,
    /** Выделенный фрагмент [text]: само использование или имя объявления. */
    val highlight: IntRange?,
    /** Функция или метод, где находится строка. */
    @Nls val context: String,
) {
    val location: String get() = "$fileName:$line"
    val highlighted: String get() = highlight?.let { text.substring(it.first, it.last + 1) } ?: ""

    fun sameLine(other: CodeLine): Boolean = filePath == other.filePath && line == other.line
}

/** Узел графа потока данных. */
class FlowNode internal constructor(
    val id: Int,
    val kind: Kind,
    @Nls val title: String,
    /** Строка, где узел объявлен или находится. */
    val code: CodeLine,
) {
    enum class Kind { VARIABLE, PARAMETER, RECEIVER, FIELD, RESULT, CALL, ORIGIN, EXTERNAL, SINK }

    internal val incomingEdges = mutableListOf<FlowEdge>()
    internal val outgoingEdges = mutableListOf<FlowEdge>()

    val incoming: List<FlowEdge> get() = incomingEdges
    val outgoing: List<FlowEdge> get() = outgoingEdges
    val context: String get() = code.context
    val location: String get() = code.location
    val snippet: String get() = code.text
    val pointer: SmartPsiElementPointer<PsiElement>? get() = code.pointer

    var isStart: Boolean = false
        internal set

    /** Дальше не исследовался: кончился бюджет узлов или глубины. */
    var isTruncated: Boolean = false
        internal set

    override fun toString(): String = "$title [$context, $location]"
}

/**
 * Ребро направлено по потоку данных: значение переходит из [from] в [to].
 * [site] — место в коде, где происходит переход: аргумент в вызове, правая часть присваивания, `return`.
 * Между одной парой узлов может быть несколько рёбер — по одному на каждое место.
 */
data class FlowEdge(val from: FlowNode, val to: FlowNode, @Nls val label: String, val kind: FlowKind, val site: CodeLine)

class FlowGraph internal constructor(
    val direction: Direction,
    val start: FlowNode,
    val nodes: List<FlowNode>,
    /** Граф неполный: сработал лимит узлов или глубины. */
    val truncated: Boolean,
) {
    /**
     * Рёбра в направлении анализа: для BACKWARD — откуда пришло, для FORWARD — куда ушло.
     * Отсортированы по месту в коде: поиск ссылок отдаёт их не по порядку строк.
     */
    fun next(node: FlowNode): List<FlowEdge> =
        (if (direction == Direction.BACKWARD) node.incoming else node.outgoing).sortedWith(BY_SITE)

    /** Конец ребра дальше по направлению анализа. */
    fun ahead(edge: FlowEdge): FlowNode = if (direction == Direction.BACKWARD) edge.from else edge.to

    /** Конец ребра ближе к стартовому узлу. */
    fun behind(edge: FlowEdge): FlowNode = if (direction == Direction.BACKWARD) edge.to else edge.from

    /** Типы рёбер первого уровня — прямых использований выбранного элемента — и их число. */
    fun firstLevelKinds(): Map<FlowKind, Int> =
        next(start).groupingBy { it.kind }.eachCount().toSortedMap()

    /**
     * Узлы, достижимые от старта, если на первом уровне оставить только рёбра типов [kinds].
     * Глубже фильтр не действует: поддерево оставленного использования показывается целиком.
     */
    fun reachableVia(kinds: Set<FlowKind>): Set<FlowNode> {
        val seen = linkedSetOf(start)
        val queue = ArrayDeque<FlowNode>()
        next(start).filter { it.kind in kinds }.map(::ahead).forEach { if (seen.add(it)) queue.add(it) }
        while (queue.isNotEmpty()) {
            for (e in next(queue.removeFirst())) {
                val n = ahead(e)
                if (seen.add(n)) queue.add(n)
            }
        }
        return seen
    }

    /**
     * Конечные точки анализа среди [visible]: для BACKWARD — самые верхние источники,
     * для FORWARD — места, дальше которых значение не уходит.
     */
    fun terminals(visible: Set<FlowNode> = nodes.toSet()): List<FlowNode> =
        nodes.filter { it != start && it in visible && next(it).isEmpty() && !isOnlyWrite(it) }
            .sortedWith(compareBy({ it.code.filePath }, { it.code.line }))

    /** Перезапись переменной — не место, куда уходит значение: показывается в своей группе, но не конечная точка. */
    private fun isOnlyWrite(node: FlowNode): Boolean {
        val into = if (direction == Direction.BACKWARD) node.outgoing else node.incoming
        return into.isNotEmpty() && into.all { it.kind == FlowKind.WRITE }
    }

    /** Кратчайшая цепочка рёбер от старта до [target] в направлении анализа. */
    fun pathFromStart(target: FlowNode, visible: Set<FlowNode> = nodes.toSet()): List<FlowEdge> {
        val via = LinkedHashMap<FlowNode, FlowEdge?>()
        val queue = ArrayDeque<FlowNode>()
        via[start] = null
        queue.add(start)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (cur == target) break
            for (e in next(cur)) {
                val n = ahead(e)
                if (n in visible && n !in via) {
                    via[n] = e
                    queue.add(n)
                }
            }
        }
        if (target !in via) return emptyList()
        return generateSequence(via[target]) { via[behind(it)] }.toList().asReversed()
    }

    /**
     * Слой узла для рисования: длиннейший путь от старта по направлению анализа.
     * Рёбра, замыкающие цикл, не учитываются, поэтому каждое прочее ребро идёт строго к следующему слою.
     */
    fun layers(visible: Set<FlowNode> = nodes.toSet()): Map<FlowNode, Int> {
        val order = ArrayList<FlowNode>()
        val state = HashMap<FlowNode, Boolean>() // false — в стеке обхода, true — обработан
        val back = HashSet<FlowEdge>()
        fun dfs(n: FlowNode) {
            state[n] = false
            for (e in next(n)) {
                val m = ahead(e)
                if (m !in visible) continue
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
        nodes.filter { it in visible && it !in state }.forEach(::dfs)
        val layer = HashMap<FlowNode, Int>()
        for (n in order.asReversed()) {
            val l = layer.getOrPut(n) { 0 }
            for (e in next(n)) {
                val m = ahead(e)
                if (e !in back && m in visible) layer[m] = maxOf(layer[m] ?: 0, l + 1)
            }
        }
        return layer
    }

    private companion object {
        val BY_SITE: Comparator<FlowEdge> =
            compareBy({ it.site.filePath }, { it.site.line }, { it.site.highlight?.first ?: 0 })
    }
}
