package io.github.lookandhate.flowtrace.analysis

import com.goide.go.GoSuperMethodSearch
import com.goide.psi.GoArgumentList
import com.goide.psi.GoAssignmentStatement
import com.goide.psi.GoBuiltinArgumentList
import com.goide.psi.GoCallExpr
import com.goide.psi.GoConversionExpr
import com.goide.psi.GoElement
import com.goide.psi.GoExpression
import com.goide.psi.GoFieldDefinition
import com.goide.psi.GoFieldName
import com.goide.psi.GoFile
import com.goide.psi.GoFunctionLit
import com.goide.psi.GoFunctionOrMethodDeclaration
import com.goide.psi.GoKey
import com.goide.psi.GoLeftHandExprList
import com.goide.psi.GoMethodDeclaration
import com.goide.psi.GoMethodSpec
import com.goide.psi.GoNamedElement
import com.goide.psi.GoParamDefinition
import com.goide.psi.GoParameters
import com.goide.psi.GoParenthesesExpr
import com.goide.psi.GoRangeClause
import com.goide.psi.GoReceiver
import com.goide.psi.GoReferenceExpression
import com.goide.psi.GoResult
import com.goide.psi.GoReturnStatement
import com.goide.psi.GoSignatureOwner
import com.goide.psi.GoStatement
import com.goide.psi.GoTypeSpec
import com.goide.psi.GoUnaryExpr
import com.goide.psi.GoValue
import com.goide.psi.GoVarDefinition
import com.goide.psi.GoVarSpec
import com.intellij.codeInsight.highlighting.ReadWriteAccessDetector
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.parentOfType
import com.intellij.util.concurrency.annotations.RequiresReadLock
import io.github.lookandhate.flowtrace.FlowTraceBundle.message
import io.github.lookandhate.flowtrace.model.Direction
import io.github.lookandhate.flowtrace.model.FlowEdge
import io.github.lookandhate.flowtrace.model.FlowGraph
import io.github.lookandhate.flowtrace.model.FlowNode
import io.github.lookandhate.flowtrace.model.FlowNode.Kind

/**
 * Межпроцедурный анализ потока значения в Go по PSI.
 *
 * Анализ нечувствителен к экземплярам и путям: поле структуры — один узел на все объекты,
 * вызов через интерфейс ведёт во все реализации в проекте. В библиотеки (stdlib, модули)
 * не заходит: библиотечный вызов — внешний источник или конечная точка.
 */
class GoFlowAnalyzer(
    private val project: Project,
    private val direction: Direction,
    private val maxNodes: Int = DEFAULT_MAX_NODES,
    private val maxDepth: Int = DEFAULT_MAX_DEPTH,
) {
    private data class Key(val element: PsiElement, val index: Int, val tag: String)
    private data class Work(val element: GoNamedElement, val node: FlowNode, val depth: Int)

    private val projectScope = GlobalSearchScope.projectScope(project)
    private val fileIndex = ProjectFileIndex.getInstance(project)
    private val pointers = SmartPointerManager.getInstance(project)
    private val nodes = LinkedHashMap<Key, FlowNode>()
    private val edges = HashSet<Pair<Int, Int>>()
    private val queue = ArrayDeque<Work>()
    private val callsCache = HashMap<GoFunctionOrMethodDeclaration, List<GoCallExpr>>()
    private var truncated = false

    @RequiresReadLock
    fun analyze(target: GoNamedElement): FlowGraph {
        val start = namedNode(target, 0).apply { isStart = true }
        while (queue.isNotEmpty()) {
            ProgressManager.checkCanceled()
            val (element, node, depth) = queue.removeFirst()
            when (direction) {
                Direction.BACKWARD -> backwardNamed(element, node, depth)
                Direction.FORWARD -> forwardNamed(element, node, depth)
            }
        }
        return FlowGraph(direction, start, nodes.values.toList(), truncated)
    }

    // ---------------------------------------------------------------- назад: откуда пришло

    private fun backwardNamed(el: GoNamedElement, node: FlowNode, depth: Int) {
        when (el) {
            is GoVarDefinition -> {
                when (val parent = el.parent) {
                    is GoRangeClause -> parent.rangeExpression?.let { origin(it, 0, node, message("edge.range"), depth) }
                    is GoVarSpec -> {
                        val defs = parent.varDefinitionList
                        val initialized = fromList(parent.expressionList, defs.size, defs.indexOf(el), node, message("edge.init"), depth)
                        if (!initialized && parent.expressionList.isEmpty()) {
                            link(syntheticOrigin(el, message("origin.zero"), "var ${el.name}"), node, message("edge.decl.no.value"))
                        }
                    }
                    else -> link(syntheticOrigin(el, message("origin.declaration"), snippetOf(el)), node, message("edge.decl"))
                }
                assignments(el, node, depth)
            }
            is GoParamDefinition -> {
                if (el.parentOfType<GoResult>() == null) backwardParam(el, node, depth)
                assignments(el, node, depth)
            }
            is GoReceiver -> {
                val method = el.parentOfType<GoMethodDeclaration>()
                if (method != null) {
                    val calls = callsOf(method)
                    for (call in calls) {
                        val qualifier = (call.expression as? GoReferenceExpression)?.qualifier as? GoExpression ?: continue
                        origin(qualifier, 0, node, message("edge.receiver.call", method.name ?: "?"), depth)
                    }
                    if (calls.isEmpty()) {
                        link(syntheticOrigin(method, message("origin.no.callers"), "${method.name}()"), node, message("edge.call"))
                    }
                }
                assignments(el, node, depth)
            }
            is GoFieldDefinition -> {
                for (ref in ReferencesSearch.search(el, projectScope).findAll()) {
                    ProgressManager.checkCanceled()
                    when (val e = ref.element) {
                        is GoFieldName -> {
                            val value = ((e.parent as? GoKey)?.parent as? GoElement)?.value ?: continue
                            val expr = value.expression
                            when {
                                expr != null -> origin(expr, 0, node, message("edge.struct.literal"), depth)
                                value.literalValue != null ->
                                    link(originNode(value, message("origin.nested.literal")), node, message("edge.struct.literal"))
                            }
                        }
                        is GoReferenceExpression -> assignmentTo(e, node, depth)
                    }
                }
            }
        }
    }

    private fun backwardParam(p: GoParamDefinition, node: FlowNode, depth: Int) {
        val params = p.parentOfType<GoParameters>() ?: return
        val owner = p.parentOfType<GoSignatureOwner>() ?: return
        if (owner !is GoFunctionOrMethodDeclaration) {
            link(syntheticOrigin(owner, message("origin.anon.param"), snippetOf(owner)), node, message("edge.anon.call"))
            return
        }
        val defs = params.definitionList
        val index = defs.indexOf(p)
        val label = message("edge.argument.n", (index + 1).toString(), owner.name ?: "?")
        val calls = callsOf(owner)
        for (call in calls) {
            val argumentList = call.argumentList
            val args = argumentList.expressionList
            when {
                args.size == 1 && defs.size > 1 && args[0].isMultiResultCall() -> origin(args[0], index, node, label, depth)
                p.isVariadic && argumentList.tripleDot == null ->
                    for (i in index until args.size) origin(args[i], 0, node, label, depth)
                index < args.size -> origin(args[index], 0, node, label, depth)
            }
        }
        val valueUses = functionValueUses(owner)
        for (use in valueUses) {
            link(originNode(use, message("origin.func.value")), node, message("edge.func.value"))
        }
        if (calls.isEmpty() && valueUses.isEmpty()) {
            link(syntheticOrigin(owner, message("origin.no.callers"), "${owner.name}()"), node, message("edge.call"))
        }
    }

    /** Все записи `x = ...` в переменную, параметр или поле. */
    private fun assignments(el: GoNamedElement, node: FlowNode, depth: Int) {
        for (ref in ReferencesSearch.search(el, projectScope).findAll()) {
            ProgressManager.checkCanceled()
            (ref.element as? GoReferenceExpression)?.let { assignmentTo(it, node, depth) }
        }
    }

    private fun assignmentTo(lhs: GoReferenceExpression, node: FlowNode, depth: Int) {
        val list = lhs.parent as? GoLeftHandExprList ?: return
        val assign = list.parent as? GoAssignmentStatement ?: return
        if (assign.assignOp.text != "=") return
        val left = list.expressionList
        fromList(assign.expressionList, left.size, left.indexOf(lhs), node, message("edge.assign"), depth)
    }

    /** Сопоставляет i-й из [count] получателей с правой частью: `a, b = x, y` или `a, b = f()`. */
    private fun fromList(rhs: List<GoExpression>, count: Int, index: Int, node: FlowNode, label: String, depth: Int): Boolean {
        if (index < 0) return false
        return when (rhs.size) {
            count -> { origin(rhs[index], 0, node, label, depth); true }
            1 -> { origin(rhs[0], index, node, label, depth); true }
            else -> false
        }
    }

    /** Откуда берётся значение выражения; [index] — номер результата для вызова с несколькими результатами. */
    private fun origin(expr: GoExpression, index: Int, to: FlowNode, label: String, depth: Int) {
        ProgressManager.checkCanceled()
        when (val e = expr.unwrap()) {
            is GoReferenceExpression -> {
                val resolved = e.reference.resolve()
                if (resolved.isTracked()) {
                    link(namedNode(resolved as GoNamedElement, depth + 1), to, label)
                } else {
                    link(originNode(e, describeOrigin(e, resolved)), to, label)
                }
            }
            is GoCallExpr -> {
                val callee = e.resolveCallee()
                val args = e.argumentList.expressionList
                if (callee is GoTypeSpec && args.size == 1) {
                    origin(args[0], 0, to, message("edge.type.conversion", label), depth)
                    return
                }
                val impls = implementations(callee)
                if (impls.isEmpty()) {
                    link(node(Key(e, index, "external"), Kind.EXTERNAL, message("node.external", e.text.shorten(50)), e), to, label)
                    return
                }
                val key = Key(e, index, "call")
                val fresh = key !in nodes
                val title = if (index > 0) message("node.call.result.n", calleeName(callee), (index + 1).toString())
                else message("node.call.result", calleeName(callee))
                val callNode = node(key, Kind.CALL, title, e)
                link(callNode, to, label)
                if (!fresh) return
                if (depth >= maxDepth || overBudget()) {
                    callNode.isTruncated = true
                    truncated = true
                    return
                }
                impls.forEach { returnSources(it, index, callNode, depth + 1) }
            }
            else -> link(originNode(e, describeOrigin(e, null)), to, label)
        }
    }

    private fun returnSources(owner: GoSignatureOwner, index: Int, to: FlowNode, depth: Int) {
        val resultCount = owner.signature.resultCount()
        val label = message("edge.return.in", functionName(owner))
        for (rs in owner.returnStatements()) {
            val exprs = rs.expressionList
            when {
                exprs.isEmpty() -> owner.signature.namedResult(index)?.let { link(namedNode(it, depth + 1), to, label) }
                exprs.size == resultCount && index < exprs.size -> origin(exprs[index], 0, to, label, depth)
                exprs.size == 1 -> origin(exprs[0], index, to, label, depth)
            }
        }
    }

    private fun describeOrigin(e: GoExpression, resolved: PsiElement?): String = when {
        resolved is GoNamedElement && resolved !is GoVarDefinition -> message("origin.const", resolved.name ?: "?")
        resolved == null && e is GoReferenceExpression -> message("origin.unresolved")
        else -> message("origin.value")
    }

    // ---------------------------------------------------------------- вперёд: куда уходит

    private fun forwardNamed(el: GoNamedElement, node: FlowNode, depth: Int) {
        for (ref in ReferencesSearch.search(el, projectScope).findAll()) {
            ProgressManager.checkCanceled()
            val re = ref.element as? GoReferenceExpression ?: continue
            if (re.readWriteAccess == ReadWriteAccessDetector.Access.Write) continue
            if (el is GoFieldDefinition) {
                // Чтение поля — отдельный узел в читающей функции: видно, где значение достают из структуры.
                val read = node(Key(re, 0, "field-read"), Kind.VARIABLE, message("node.field.read", re.text.shorten(40)), re)
                link(node, read, message("edge.field.read"))
                flowInto(re, 0, read, depth)
            } else {
                flowInto(re, 0, node, depth)
            }
        }
        if (el is GoParamDefinition && el.parentOfType<GoResult>() != null) {
            val owner = el.parentOfType<GoSignatureOwner>() ?: return
            val index = el.parentOfType<GoParameters>()?.definitionList?.indexOf(el) ?: 0
            flowOutOfReturn(owner, index, node, depth)
        }
    }

    /** Куда попадает значение выражения [use]; [index] — номер результата, если это вызов. */
    private fun flowInto(use: GoExpression, index: Int, from: FlowNode, depth: Int) {
        ProgressManager.checkCanceled()
        var cur: PsiElement = use
        var parent = cur.parent
        while (parent is GoParenthesesExpr || parent is GoConversionExpr ||
            (parent is GoUnaryExpr && (parent.bitAnd != null || parent.mul != null))
        ) {
            cur = parent
            parent = parent.parent
        }

        when {
            parent is GoArgumentList && parent.parent is GoCallExpr ->
                flowIntoArgument(cur, parent, parent.parent as GoCallExpr, index, from, depth)

            parent is GoBuiltinArgumentList ->
                link(from, sink(cur, message("sink.builtin", parent.parent.text.shorten(50))), message("edge.argument"))

            parent is GoAssignmentStatement && cur in parent.expressionList -> {
                val rhs = parent.expressionList
                val lhs = parent.leftHandExprList.expressionList
                val target = when {
                    rhs.size == lhs.size -> lhs[rhs.indexOf(cur)]
                    rhs.size == 1 -> lhs.getOrNull(index)
                    else -> null
                }
                target?.let { assignTarget(it, from, depth) }
            }

            parent is GoVarSpec && parent !is GoRangeClause && cur in parent.expressionList -> {
                val rhs = parent.expressionList
                val defs = parent.varDefinitionList
                val target = when {
                    rhs.size == defs.size -> defs[rhs.indexOf(cur)]
                    rhs.size == 1 -> defs.getOrNull(index)
                    else -> null
                } ?: return
                if (target.name == "_") link(from, sink(cur, message("sink.discarded")), message("edge.init"))
                else link(from, namedNode(target, depth + 1), message("edge.init"))
            }

            parent is GoValue && parent.parent is GoElement -> {
                val element = parent.parent as GoElement
                val field = element.key?.fieldName?.reference?.resolve()
                if (field is GoFieldDefinition) {
                    link(from, namedNode(field, depth + 1), message("edge.struct.literal"))
                } else {
                    link(from, sink(cur, message("sink.literal.element", literalType(element).shorten(40))), message("edge.literal"))
                }
            }

            parent is GoReturnStatement -> {
                val exprs = parent.expressionList
                val resultIndex = if (exprs.size == 1) index else exprs.indexOf(cur)
                val owner = PsiTreeUtil.getParentOfType(parent, GoFunctionLit::class.java, GoFunctionOrMethodDeclaration::class.java)
                    ?: return
                val key = Key(owner, resultIndex, "result")
                val fresh = key !in nodes
                val title = if (resultIndex > 0) message("node.call.result.n", functionName(owner), (resultIndex + 1).toString())
                else message("node.call.result", functionName(owner))
                val resultNode = node(key, Kind.RESULT, title, owner)
                link(from, resultNode, message("edge.return"))
                if (fresh) flowOutOfReturn(owner, resultIndex, resultNode, depth + 1)
            }

            parent is GoReferenceExpression && parent.qualifier == cur -> flowIntoQualifier(cur, parent, from, depth)

            else -> {
                val text = (cur.parentOfType<GoStatement>() ?: cur).text
                link(from, sink(cur, text.shorten(60)), message("edge.use"))
            }
        }
    }

    private fun flowIntoArgument(cur: PsiElement, argumentList: GoArgumentList, call: GoCallExpr, index: Int, from: FlowNode, depth: Int) {
        val args = argumentList.expressionList
        val argIndex = if (args.size == 1 && args[0].isMultiResultCall()) index else args.indexOf(cur)
        val callee = call.resolveCallee()
        if (callee is GoTypeSpec) {
            flowInto(call, 0, from, depth)
            return
        }
        val impls = implementations(callee)
        if (impls.isEmpty()) {
            val calleeText = call.expression.text.shorten(50)
            link(from, sink(cur, message("sink.passed", calleeText)), message("edge.argument"))
            return
        }
        for (impl in impls) {
            val defs = impl.signature?.parameters?.definitionList ?: continue
            if (defs.isEmpty()) {
                link(from, sink(cur, message("sink.passed.unnamed", functionName(impl))), message("edge.argument"))
                continue
            }
            if (argIndex < 0 || (argIndex >= defs.size && !defs.last().isVariadic)) continue
            val param = defs[minOf(argIndex, defs.size - 1)]
            link(from, namedNode(param, depth + 1), message("edge.argument.n", (argIndex + 1).toString(), functionName(impl)))
        }
    }

    /** Значение стоит слева от точки: `v.Method()` или `v.field`. */
    private fun flowIntoQualifier(cur: PsiElement, q: GoReferenceExpression, from: FlowNode, depth: Int) {
        val name = q.identifier.text
        val call = q.asCalleeOfCall()
        if (call == null) {
            val what = if (q.reference.resolve() is GoFieldDefinition) "sink.field.read" else "sink.access"
            link(from, sink(q, message(what, name)), message("edge.access"))
            return
        }
        val impls = implementations(q.reference.resolve())
        // Узел привязан к месту вызова, чтобы в цепочке была функция, которая вызывает метод.
        val callNode = node(
            Key(q, 0, "method-call"), if (impls.isEmpty()) Kind.SINK else Kind.CALL,
            message("node.method.call", cur.text.shorten(30), name), q,
        )
        link(from, callNode, message("edge.method.call"))
        for (impl in impls) {
            val receiver = (impl as? GoMethodDeclaration)?.receiver ?: continue
            if (receiver.identifier == null || receiver.name == "_") continue
            link(callNode, namedNode(receiver, depth + 1), message("edge.receiver", functionName(impl)))
        }
    }

    private fun flowOutOfReturn(owner: GoSignatureOwner, index: Int, from: FlowNode, depth: Int) {
        if (owner !is GoFunctionOrMethodDeclaration) {
            link(from, sink(owner, message("sink.returned.anon")), message("edge.return"))
            return
        }
        if (depth >= maxDepth || overBudget()) {
            from.isTruncated = true
            truncated = true
            return
        }
        callsOf(owner).forEach { flowInto(it, index, from, depth) }
    }

    private fun assignTarget(target: GoExpression, from: FlowNode, depth: Int) {
        val t = target.unwrap()
        if (t is GoReferenceExpression) {
            if (t.text == "_") {
                link(from, sink(t, message("sink.discarded")), message("edge.assign"))
                return
            }
            val resolved = t.reference.resolve()
            if (resolved.isTracked()) {
                link(from, namedNode(resolved as GoNamedElement, depth + 1), message("edge.assign"))
                return
            }
        }
        link(from, sink(t, message("sink.stored", t.text.shorten(40))), message("edge.assign"))
    }

    private fun literalType(element: GoElement): String {
        val text = element.parent?.parent?.text ?: ""
        val brace = text.indexOf('{')
        return if (brace > 0) text.substring(0, brace) + "{}" else "{}"
    }

    // ---------------------------------------------------------------- вызовы и реализации

    /** Вызовы функции или метода в проекте, включая вызовы через интерфейсы, которые метод реализует. */
    private fun callsOf(decl: GoFunctionOrMethodDeclaration): List<GoCallExpr> = callsCache.getOrPut(decl) {
        withSuperMethods(decl)
            .flatMap { ReferencesSearch.search(it, projectScope).findAll() }
            .mapNotNull { ProgressManager.checkCanceled(); it.element.asCalleeOfCall() }
            .distinct()
    }

    /** Упоминания функции не в позиции вызова: передача как значения, сохранение в поле. */
    private fun functionValueUses(decl: GoFunctionOrMethodDeclaration): List<PsiElement> =
        ReferencesSearch.search(decl, projectScope).findAll().map { it.element }.filter { it.asCalleeOfCall() == null }

    private fun withSuperMethods(decl: GoFunctionOrMethodDeclaration): List<GoNamedElement> {
        val result = mutableListOf<GoNamedElement>(decl)
        if (decl is GoMethodDeclaration) {
            GoSuperMethodSearch.GO_SUPER_METHOD_SEARCH.execute(DefinitionsScopedSearch.SearchParameters(decl)) { spec ->
                if (spec is GoMethodSpec && spec.inProject()) result += spec
                true
            }
        }
        return result
    }

    /** Тела, в которые может попасть вызов: сама функция или реализации метода интерфейса. Только код проекта. */
    private fun implementations(callee: PsiElement?): List<GoSignatureOwner> = when (callee) {
        is GoFunctionOrMethodDeclaration -> listOfNotNull(callee.takeIf { it.inProject() && it.block != null })
        is GoMethodSpec -> DefinitionsScopedSearch.search(callee, projectScope).findAll()
            .filterIsInstance<GoMethodDeclaration>()
            .filter { it.inProject() && it.block != null }
        else -> emptyList()
    }

    private fun PsiElement.inProject(): Boolean {
        val vf = containingFile?.virtualFile ?: return false
        return fileIndex.isInContent(vf) && !fileIndex.isInLibrary(vf)
    }

    // ---------------------------------------------------------------- узлы и рёбра

    private fun overBudget() = nodes.size >= maxNodes

    private fun namedNode(el: GoNamedElement, depth: Int): FlowNode {
        val key = Key(el, 0, "named")
        nodes[key]?.let { return it }
        val name = el.name ?: "?"
        val (kind, title) = when {
            el is GoFieldDefinition ->
                Kind.FIELD to message("node.field", (el.parentOfType<GoTypeSpec>()?.let { "${it.name}." } ?: "") + name)
            el is GoReceiver -> Kind.RECEIVER to message("node.receiver", name)
            el is GoParamDefinition && el.parentOfType<GoResult>() != null -> Kind.RESULT to message("node.result.named", name)
            el is GoParamDefinition -> Kind.PARAMETER to message("node.parameter", name)
            else -> Kind.VARIABLE to message("node.variable", name)
        }
        val node = node(key, kind, title, el)
        if (depth > maxDepth || nodes.size > maxNodes) {
            node.isTruncated = true
            truncated = true
        } else {
            queue += Work(el, node, depth)
        }
        return node
    }

    private fun originNode(e: PsiElement, what: String) =
        node(Key(e, 0, "origin"), Kind.ORIGIN, message("node.origin", e.text.shorten(50), what), e)

    private fun syntheticOrigin(anchor: PsiElement, what: String, text: String) =
        node(Key(anchor, 0, "synthetic:$what"), Kind.ORIGIN, message("node.synthetic", what, text.shorten(50)), anchor)

    private fun sink(e: PsiElement, what: String) = node(Key(e, 0, "sink"), Kind.SINK, what, e)

    private fun node(key: Key, kind: Kind, title: String, anchor: PsiElement): FlowNode = nodes.getOrPut(key) {
        FlowNode(
            id = nodes.size, kind = kind, title = title,
            snippet = snippetOf(anchor), context = contextOf(anchor), location = locationOf(anchor),
            pointer = pointers.createSmartPsiElementPointer(anchor),
        )
    }

    private fun link(from: FlowNode, to: FlowNode, label: String) {
        if (from === to || !edges.add(from.id to to.id)) return
        val edge = FlowEdge(from, to, label)
        from.outgoingEdges += edge
        to.incomingEdges += edge
    }

    companion object {
        const val DEFAULT_MAX_NODES = 400
        const val DEFAULT_MAX_DEPTH = 40

        /** Переменная, параметр, получатель или поле под курсором; null, если там не они. */
        @RequiresReadLock
        fun findTarget(file: PsiFile, offset: Int): GoNamedElement? {
            if (file !is GoFile) return null
            // Курсор сразу за именем («x|.f») — сначала смотрим символ слева, иначе попадём в «.f».
            val leaf = file.findElementAt(offset)
            val onIdentifier = leaf?.text?.firstOrNull()?.let(Character::isJavaIdentifierPart) == true
            return (if (onIdentifier) targetAt(file, offset) else null)
                ?: (if (offset > 0) targetAt(file, offset - 1) else null)
                ?: (if (!onIdentifier) targetAt(file, offset) else null)
        }

        private fun targetAt(file: PsiFile, offset: Int): GoNamedElement? {
            val leaf = file.findElementAt(offset) ?: return null
            val parent = leaf.parent
            if (parent is GoReferenceExpression || parent is GoFieldName) {
                return parent.reference.resolve()?.takeIf { it.isTracked() } as GoNamedElement?
            }
            return (parent as? GoNamedElement)?.takeIf { it.isTracked() && it.identifier == leaf }
        }
    }
}
