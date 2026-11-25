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
import com.goide.psi.GoIncDecStatement
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
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
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
import io.github.lookandhate.flowtrace.model.CodeLine
import io.github.lookandhate.flowtrace.model.Direction
import io.github.lookandhate.flowtrace.model.FlowEdge
import io.github.lookandhate.flowtrace.model.FlowGraph
import io.github.lookandhate.flowtrace.model.FlowKind
import io.github.lookandhate.flowtrace.model.FlowNode
import io.github.lookandhate.flowtrace.model.FlowNode.Kind

/**
 * Межпроцедурный анализ потока значения в Go по PSI.
 *
 * Анализ нечувствителен к экземплярам и путям: поле структуры — один узел на все объекты,
 * вызов через интерфейс ведёт во все реализации в проекте. В библиотеки (stdlib, модули)
 * не заходит: библиотечный вызов — внешний источник или конечная точка.
 *
 * Каждое ребро помнит место в коде ([FlowEdge.site]) и тип шага ([FlowKind]): одно использование — одно ребро.
 */
class GoFlowAnalyzer(
    private val project: Project,
    private val direction: Direction,
    private val maxNodes: Int = DEFAULT_MAX_NODES,
    private val maxDepth: Int = DEFAULT_MAX_DEPTH,
) {
    private data class Key(val element: PsiElement, val index: Int, val tag: String)
    private data class Work(val element: GoNamedElement, val node: FlowNode, val depth: Int)
    private data class EdgeKey(val from: Int, val to: Int, val site: PsiElement)

    private val projectScope = GlobalSearchScope.projectScope(project)
    private val fileIndex = ProjectFileIndex.getInstance(project)
    private val pointers = SmartPointerManager.getInstance(project)
    private val nodes = LinkedHashMap<Key, FlowNode>()
    private val edges = HashSet<EdgeKey>()
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
                    is GoRangeClause -> parent.rangeExpression?.let {
                        origin(it, 0, node, message("edge.range"), FlowKind.OTHER, depth)
                    }
                    is GoVarSpec -> {
                        val defs = parent.varDefinitionList
                        val initialized = fromList(parent.expressionList, defs.size, defs.indexOf(el), node,
                            message("edge.init"), FlowKind.ASSIGNMENT, depth)
                        if (!initialized && parent.expressionList.isEmpty()) {
                            link(syntheticOrigin(el, message("origin.zero"), "var ${el.name}"), node,
                                message("edge.decl.no.value"), FlowKind.DECLARATION, el)
                        }
                    }
                    else -> link(syntheticOrigin(el, message("origin.declaration"), snippetOf(el)), node,
                        message("edge.decl"), FlowKind.DECLARATION, el)
                }
                writes(el, node, FlowKind.ASSIGNMENT, depth)
            }
            is GoParamDefinition -> {
                if (el.parentOfType<GoResult>() == null) backwardParam(el, node, depth)
                writes(el, node, FlowKind.ASSIGNMENT, depth)
            }
            is GoReceiver -> {
                val method = el.parentOfType<GoMethodDeclaration>()
                if (method != null) {
                    val calls = callsOf(method)
                    for (call in calls) {
                        val qualifier = (call.expression as? GoReferenceExpression)?.qualifier as? GoExpression ?: continue
                        origin(qualifier, 0, node, message("edge.receiver.call", method.name ?: "?"), FlowKind.METHOD_CALL, depth)
                    }
                    if (calls.isEmpty()) {
                        link(syntheticOrigin(method, message("origin.no.callers"), "${method.name}()"), node,
                            message("edge.call"), FlowKind.DECLARATION, method)
                    }
                }
                writes(el, node, FlowKind.ASSIGNMENT, depth)
            }
            is GoFieldDefinition -> {
                for (ref in ReferencesSearch.search(el, projectScope).findAll()) {
                    ProgressManager.checkCanceled()
                    val e = ref.element
                    if (e is GoFieldName) {
                        val value = ((e.parent as? GoKey)?.parent as? GoElement)?.value ?: continue
                        val expr = value.expression
                        when {
                            expr != null -> origin(expr, 0, node, message("edge.struct.literal"), FlowKind.FIELD, depth)
                            value.literalValue != null -> link(originNode(value, message("origin.nested.literal")), node,
                                message("edge.struct.literal"), FlowKind.FIELD, value)
                        }
                    }
                }
                writes(el, node, FlowKind.FIELD, depth)
            }
        }
    }

    private fun backwardParam(p: GoParamDefinition, node: FlowNode, depth: Int) {
        val params = p.parentOfType<GoParameters>() ?: return
        val owner = p.parentOfType<GoSignatureOwner>() ?: return
        if (owner !is GoFunctionOrMethodDeclaration) {
            link(syntheticOrigin(owner, message("origin.anon.param"), snippetOf(owner)), node,
                message("edge.anon.call"), FlowKind.DECLARATION, owner)
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
                args.size == 1 && defs.size > 1 && args[0].isMultiResultCall() ->
                    origin(args[0], index, node, label, FlowKind.ARGUMENT, depth)
                p.isVariadic && argumentList.tripleDot == null ->
                    for (i in index until args.size) origin(args[i], 0, node, label, FlowKind.ARGUMENT, depth)
                index < args.size -> origin(args[index], 0, node, label, FlowKind.ARGUMENT, depth)
            }
        }
        val valueUses = functionValueUses(owner)
        for (use in valueUses) {
            link(originNode(use, message("origin.func.value")), node, message("edge.func.value"), FlowKind.DECLARATION, use)
        }
        if (calls.isEmpty() && valueUses.isEmpty()) {
            link(syntheticOrigin(owner, message("origin.no.callers"), "${owner.name}()"), node,
                message("edge.call"), FlowKind.DECLARATION, owner)
        }
    }

    /** Все записи в переменную, параметр или поле: `x = …`, `x += …`, `x++`, повторное `a, x := …`. */
    private fun writes(el: GoNamedElement, node: FlowNode, kind: FlowKind, depth: Int) {
        for (ref in ReferencesSearch.search(el, projectScope).findAll()) {
            ProgressManager.checkCanceled()
            when (val e = ref.element) {
                is GoReferenceExpression -> writeTo(e, node, kind, depth)
                is GoVarDefinition -> redeclaration(e, node, depth)
            }
        }
    }

    private fun writeTo(lhs: GoReferenceExpression, node: FlowNode, kind: FlowKind, depth: Int) {
        val parent = lhs.parent
        if (parent is GoIncDecStatement) {
            link(syntheticOrigin(parent, message("origin.incdec"), parent.text), node, message("edge.assign"), kind, parent)
            return
        }
        val list = parent as? GoLeftHandExprList ?: return
        val assign = list.parent as? GoAssignmentStatement ?: return
        val left = list.expressionList
        val label = if (assign.assignOp.text == "=") message("edge.assign") else message("edge.compound.assign", assign.assignOp.text)
        fromList(assign.expressionList, left.size, left.indexOf(lhs), node, label, kind, depth)
    }

    /** Повторное объявление `a, x := f()`: в Go это присваивание уже существующей x. */
    private fun redeclaration(def: GoVarDefinition, node: FlowNode, depth: Int) {
        val spec = def.parent as? GoVarSpec ?: return
        val defs = spec.varDefinitionList
        fromList(spec.expressionList, defs.size, defs.indexOf(def), node, message("edge.assign"), FlowKind.ASSIGNMENT, depth)
    }

    /** Сопоставляет i-й из [count] получателей с правой частью: `a, b = x, y` или `a, b = f()`. */
    private fun fromList(
        rhs: List<GoExpression>, count: Int, index: Int, node: FlowNode, label: String, kind: FlowKind, depth: Int,
    ): Boolean {
        if (index < 0) return false
        return when (rhs.size) {
            count -> { origin(rhs[index], 0, node, label, kind, depth); true }
            1 -> { origin(rhs[0], index, node, label, kind, depth); true }
            else -> false
        }
    }

    /**
     * Откуда берётся значение выражения [expr]; [index] — номер результата для вызова с несколькими результатами.
     * [expr] — место перехода: ребро в [to] получает его строку.
     */
    private fun origin(expr: GoExpression, index: Int, to: FlowNode, label: String, kind: FlowKind, depth: Int) {
        ProgressManager.checkCanceled()
        when (val e = expr.unwrap()) {
            is GoReferenceExpression -> {
                val resolved = e.reference.resolve()
                if (resolved.isTracked()) {
                    link(namedNode(resolved as GoNamedElement, depth + 1), to, label, kind, expr)
                } else {
                    link(originNode(e, describeOrigin(e, resolved)), to, label, kind, expr)
                }
            }
            is GoCallExpr -> {
                val callee = e.resolveCallee()
                val args = e.argumentList.expressionList
                if (callee is GoTypeSpec && args.size == 1) {
                    origin(args[0], 0, to, message("edge.type.conversion", label), kind, depth)
                    return
                }
                val impls = implementations(callee)
                if (impls.isEmpty()) {
                    val external = node(Key(e, index, "external"), Kind.EXTERNAL, message("node.external", e.text.shorten(50)), e)
                    link(external, to, label, kind, expr)
                    return
                }
                val key = Key(e, index, "call")
                val fresh = key !in nodes
                val title = if (index > 0) message("node.call.result.n", calleeName(callee), (index + 1).toString())
                else message("node.call.result", calleeName(callee))
                val callNode = node(key, Kind.CALL, title, e)
                link(callNode, to, label, kind, expr)
                if (!fresh) return
                if (depth >= maxDepth || overBudget()) {
                    callNode.isTruncated = true
                    truncated = true
                    return
                }
                impls.forEach { returnSources(it, index, callNode, depth + 1) }
            }
            else -> link(originNode(e, describeOrigin(e, null)), to, label, kind, expr)
        }
    }

    private fun returnSources(owner: GoSignatureOwner, index: Int, to: FlowNode, depth: Int) {
        val resultCount = owner.signature.resultCount()
        val label = message("edge.return.in", functionName(owner))
        for (rs in owner.returnStatements()) {
            val exprs = rs.expressionList
            when {
                exprs.isEmpty() -> owner.signature.namedResult(index)?.let {
                    link(namedNode(it, depth + 1), to, label, FlowKind.RETURN, rs)
                }
                exprs.size == resultCount && index < exprs.size -> origin(exprs[index], 0, to, label, FlowKind.RETURN, depth)
                exprs.size == 1 -> origin(exprs[0], index, to, label, FlowKind.RETURN, depth)
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
            when (val re = ref.element) {
                // Повторное `a, x := …` — перезапись переменной.
                is GoVarDefinition -> link(node, sink(re, message("sink.write", re.text)), message("edge.write"), FlowKind.WRITE, re)
                is GoReferenceExpression -> forwardReference(el, re, node, depth)
            }
        }
        if (el is GoParamDefinition && el.parentOfType<GoResult>() != null) {
            val owner = el.parentOfType<GoSignatureOwner>() ?: return
            val index = el.parentOfType<GoParameters>()?.definitionList?.indexOf(el) ?: 0
            flowOutOfReturn(owner, index, node, depth)
        }
    }

    private fun forwardReference(el: GoNamedElement, re: GoReferenceExpression, node: FlowNode, depth: Int) {
        val access = re.readWriteAccess
        if (access == ReadWriteAccessDetector.Access.Write || access == ReadWriteAccessDetector.Access.ReadWrite) {
            // Запись в саму переменную (`x = …`, `x += …`, `x++`): показываем, но дальше не идём — это уже другое значение.
            val statement = re.parentOfType<GoStatement>() ?: re
            val kind = if (el is GoFieldDefinition) FlowKind.FIELD else FlowKind.WRITE
            link(node, sink(re, message("sink.write", statement.text.shorten(60))), message("edge.write"), kind, re)
            return
        }
        if (el is GoFieldDefinition) {
            // Чтение поля — отдельный узел в читающей функции: видно, где значение достают из структуры.
            val read = node(Key(re, 0, "field-read"), Kind.VARIABLE, message("node.field.read", re.text.shorten(40)), re)
            link(node, read, message("edge.field.read"), FlowKind.FIELD, re)
            flowInto(re, 0, read, depth)
        } else {
            flowInto(re, 0, node, depth)
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
        val site = cur

        when {
            parent is GoArgumentList && parent.parent is GoCallExpr ->
                flowIntoArgument(site, parent, parent.parent as GoCallExpr, index, from, depth)

            parent is GoBuiltinArgumentList ->
                link(from, sink(site, message("sink.builtin", parent.parent.text.shorten(50))), message("edge.argument"), FlowKind.OTHER, site)

            parent is GoAssignmentStatement && site in parent.expressionList -> {
                val rhs = parent.expressionList
                val lhs = parent.leftHandExprList.expressionList
                val target = when {
                    rhs.size == lhs.size -> lhs[rhs.indexOf(site)]
                    rhs.size == 1 -> lhs.getOrNull(index)
                    else -> null
                }
                target?.let { assignTarget(it, site, from, depth) }
            }

            parent is GoVarSpec && parent !is GoRangeClause && site in parent.expressionList -> {
                val rhs = parent.expressionList
                val defs = parent.varDefinitionList
                val target = when {
                    rhs.size == defs.size -> defs[rhs.indexOf(site)]
                    rhs.size == 1 -> defs.getOrNull(index)
                    else -> null
                } ?: return
                if (target.name == "_") link(from, sink(site, message("sink.discarded")), message("edge.init"), FlowKind.ASSIGNMENT, site)
                else link(from, namedNode(target, depth + 1), message("edge.init"), FlowKind.ASSIGNMENT, site)
            }

            parent is GoValue && parent.parent is GoElement -> {
                val element = parent.parent as GoElement
                val field = element.key?.fieldName?.reference?.resolve()
                if (field is GoFieldDefinition) {
                    link(from, namedNode(field, depth + 1), message("edge.struct.literal"), FlowKind.FIELD, site)
                } else {
                    link(from, sink(site, message("sink.literal.element", literalType(element).shorten(40))),
                        message("edge.literal"), FlowKind.FIELD, site)
                }
            }

            parent is GoReturnStatement -> {
                val exprs = parent.expressionList
                val resultIndex = if (exprs.size == 1) index else exprs.indexOf(site)
                val owner = PsiTreeUtil.getParentOfType(parent, GoFunctionLit::class.java, GoFunctionOrMethodDeclaration::class.java)
                    ?: return
                val key = Key(owner, resultIndex, "result")
                val fresh = key !in nodes
                val title = if (resultIndex > 0) message("node.call.result.n", functionName(owner), (resultIndex + 1).toString())
                else message("node.call.result", functionName(owner))
                val resultNode = node(key, Kind.RESULT, title, owner)
                link(from, resultNode, message("edge.return"), FlowKind.RETURN, site)
                if (fresh) flowOutOfReturn(owner, resultIndex, resultNode, depth + 1)
            }

            parent is GoReferenceExpression && parent.qualifier == site -> flowIntoQualifier(site, parent, from, depth)

            else -> {
                val text = (site.parentOfType<GoStatement>() ?: site).text
                link(from, sink(site, text.shorten(60)), message("edge.use"), FlowKind.OTHER, site)
            }
        }
    }

    private fun flowIntoArgument(site: PsiElement, argumentList: GoArgumentList, call: GoCallExpr, index: Int, from: FlowNode, depth: Int) {
        val args = argumentList.expressionList
        val argIndex = if (args.size == 1 && args[0].isMultiResultCall()) index else args.indexOf(site)
        val callee = call.resolveCallee()
        if (callee is GoTypeSpec) {
            flowInto(call, 0, from, depth)
            return
        }
        val impls = implementations(callee)
        if (impls.isEmpty()) {
            val calleeText = call.expression.text.shorten(50)
            link(from, sink(site, message("sink.passed", calleeText)), message("edge.argument"), FlowKind.ARGUMENT, site)
            return
        }
        for (impl in impls) {
            val defs = impl.signature?.parameters?.definitionList ?: continue
            if (defs.isEmpty()) {
                link(from, sink(site, message("sink.passed.unnamed", functionName(impl))), message("edge.argument"), FlowKind.ARGUMENT, site)
                continue
            }
            if (argIndex < 0 || (argIndex >= defs.size && !defs.last().isVariadic)) continue
            val param = defs[minOf(argIndex, defs.size - 1)]
            link(from, namedNode(param, depth + 1), message("edge.argument.n", (argIndex + 1).toString(), functionName(impl)),
                FlowKind.ARGUMENT, site)
        }
    }

    /** Значение стоит слева от точки: `v.Method()` или `v.field`. */
    private fun flowIntoQualifier(site: PsiElement, q: GoReferenceExpression, from: FlowNode, depth: Int) {
        val name = q.identifier.text
        val call = q.asCalleeOfCall()
        if (call == null) {
            val what = if (q.reference.resolve() is GoFieldDefinition) "sink.field.read" else "sink.access"
            link(from, sink(q, message(what, name)), message("edge.access"), FlowKind.FIELD, site)
            return
        }
        val impls = implementations(q.reference.resolve())
        // Узел привязан к месту вызова, чтобы в цепочке была функция, которая вызывает метод.
        val callNode = node(
            Key(q, 0, "method-call"), if (impls.isEmpty()) Kind.SINK else Kind.CALL,
            message("node.method.call", site.text.shorten(30), name), q,
        )
        link(from, callNode, message("edge.method.call"), FlowKind.METHOD_CALL, site)
        for (impl in impls) {
            val receiver = (impl as? GoMethodDeclaration)?.receiver ?: continue
            if (receiver.identifier == null || receiver.name == "_") continue
            link(callNode, namedNode(receiver, depth + 1), message("edge.receiver", functionName(impl)), FlowKind.METHOD_CALL, q)
        }
    }

    private fun flowOutOfReturn(owner: GoSignatureOwner, index: Int, from: FlowNode, depth: Int) {
        if (owner !is GoFunctionOrMethodDeclaration) {
            link(from, sink(owner, message("sink.returned.anon")), message("edge.return"), FlowKind.RETURN, owner)
            return
        }
        if (depth >= maxDepth || overBudget()) {
            from.isTruncated = true
            truncated = true
            return
        }
        callsOf(owner).forEach { flowInto(it, index, from, depth) }
    }

    private fun assignTarget(target: GoExpression, site: PsiElement, from: FlowNode, depth: Int) {
        val t = target.unwrap()
        if (t is GoReferenceExpression) {
            if (t.text == "_") {
                link(from, sink(site, message("sink.discarded")), message("edge.assign"), FlowKind.ASSIGNMENT, site)
                return
            }
            val resolved = t.reference.resolve()
            if (resolved.isTracked()) {
                val kind = if (resolved is GoFieldDefinition) FlowKind.FIELD else FlowKind.ASSIGNMENT
                link(from, namedNode(resolved as GoNamedElement, depth + 1), message("edge.assign"), kind, site)
                return
            }
        }
        link(from, sink(site, message("sink.stored", t.text.shorten(40))), message("edge.assign"), FlowKind.OTHER, site)
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
        // В строке объявления выделяем только имя.
        val node = node(key, kind, title, el, el.identifier?.textRange ?: el.textRange)
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

    private fun node(key: Key, kind: Kind, title: String, anchor: PsiElement, highlight: TextRange = anchor.textRange): FlowNode =
        nodes.getOrPut(key) { FlowNode(id = nodes.size, kind = kind, title = title, code = codeLine(anchor, highlight)) }

    /** Одно ребро на каждое место в коде: два вызова `use(x)` дают два ребра в один параметр. */
    private fun link(from: FlowNode, to: FlowNode, label: String, kind: FlowKind, site: PsiElement) {
        if (!edges.add(EdgeKey(from.id, to.id, site))) return
        val edge = FlowEdge(from, to, label, kind, codeLine(site, site.textRange))
        from.outgoingEdges += edge
        to.incomingEdges += edge
    }

    /** Строка кода с элементом: номер, текст без отступа, выделенный фрагмент, функция. */
    private fun codeLine(anchor: PsiElement, highlight: TextRange): CodeLine {
        val file = anchor.containingFile
        val vf = file?.virtualFile
        val text = file?.viewProvider?.contents ?: ""
        val doc = file?.let { PsiDocumentManager.getInstance(project).getDocument(it) }
        val offset = highlight.startOffset.coerceIn(0, text.length)
        val lineIndex = doc?.takeIf { offset <= it.textLength }?.getLineNumber(offset)
            ?: text.subSequence(0, offset).count { it == '\n' }
        val lineStart = text.lastIndexOf('\n', offset - 1).let { if (offset == 0) 0 else it + 1 }
        val lineEnd = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
        val raw = text.subSequence(lineStart, lineEnd).toString()
        val indent = raw.length - raw.trimStart().length
        val trimmed = raw.trim()
        val from = (offset - lineStart - indent).coerceIn(0, trimmed.length)
        val to = (minOf(highlight.endOffset, lineEnd) - lineStart - indent).coerceIn(from, trimmed.length)
        return CodeLine(
            pointer = pointers.createSmartPsiElementPointer(anchor),
            filePath = vf?.path ?: file?.name ?: "",
            fileName = file?.name ?: "",
            fileType = file?.fileType,
            line = lineIndex + 1,
            text = trimmed,
            highlight = if (to > from) from until to else null,
            context = contextOf(anchor),
        )
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
