package io.github.lookandhate.flowtrace.analysis

import com.goide.psi.GoCallExpr
import com.goide.psi.GoConversionExpr
import com.goide.psi.GoExpression
import com.goide.psi.GoFieldDefinition
import com.goide.psi.GoFile
import com.goide.psi.GoFunctionDeclaration
import com.goide.psi.GoFunctionLit
import com.goide.psi.GoFunctionOrMethodDeclaration
import com.goide.psi.GoMethodDeclaration
import com.goide.psi.GoMethodSpec
import com.goide.psi.GoNamedElement
import com.goide.psi.GoParamDefinition
import com.goide.psi.GoParenthesesExpr
import com.goide.psi.GoReceiver
import com.goide.psi.GoReferenceExpression
import com.goide.psi.GoReturnStatement
import com.goide.psi.GoSignature
import com.goide.psi.GoSignatureOwner
import com.goide.psi.GoTypeSpec
import com.goide.psi.GoUnaryExpr
import com.goide.psi.GoVarDefinition
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.parentOfType
import io.github.lookandhate.flowtrace.FlowTraceBundle.message

/** Элементы, за которыми следит анализ: переменная, параметр, получатель, поле. */
internal fun PsiElement?.isTracked(): Boolean =
    this is GoVarDefinition || this is GoParamDefinition || this is GoReceiver || this is GoFieldDefinition

/** Снимает скобки, `&x`, `*x` и приведение типа — значение остаётся тем же объектом. */
internal fun GoExpression.unwrap(): GoExpression {
    var e = this
    while (true) {
        e = when {
            e is GoParenthesesExpr && e.expression != null -> e.expression!!
            e is GoUnaryExpr && (e.bitAnd != null || e.mul != null) && e.expression != null -> e.expression!!
            e is GoConversionExpr && e.expression != null -> e.expression!!
            else -> return e
        }
    }
}

internal fun GoCallExpr.resolveCallee(): PsiElement? {
    var fn = expression
    while (fn is GoParenthesesExpr) fn = fn.expression ?: return null
    return (fn as? GoReferenceExpression)?.reference?.resolve()
}

/** Одиночный аргумент `f(g())`, где g возвращает несколько значений, раздаётся по параметрам f. */
internal fun GoExpression.isMultiResultCall(): Boolean {
    val call = unwrap() as? GoCallExpr ?: return false
    val owner = call.resolveCallee() as? GoSignatureOwner ?: return false
    return owner.signature.resultCount() > 1
}

internal fun GoSignature?.resultCount(): Int {
    val result = this?.result ?: return 0
    result.parameters?.let { params ->
        return params.parameterDeclarationList.sumOf { maxOf(1, it.paramDefinitionList.size) }
    }
    return if (result.type != null) 1 else 0
}

internal fun GoSignature?.namedResult(index: Int): GoParamDefinition? =
    this?.result?.parameters?.definitionList?.getOrNull(index)

/** `return` самой функции, без вложенных func-литералов. */
internal fun GoSignatureOwner.returnStatements(): List<GoReturnStatement> {
    val body = when (this) {
        is GoFunctionOrMethodDeclaration -> block
        is GoFunctionLit -> block
        else -> null
    } ?: return emptyList()
    return PsiTreeUtil.findChildrenOfType(body, GoReturnStatement::class.java).filter {
        PsiTreeUtil.getParentOfType(it, GoFunctionLit::class.java, GoFunctionOrMethodDeclaration::class.java) == this
    }
}

/** Вызов, в котором элемент стоит на месте вызываемой функции: `f` в `f(x)`. Для `register(f)` — null. */
internal fun PsiElement.asCalleeOfCall(): GoCallExpr? {
    var up: PsiElement = this
    while (up.parent is GoParenthesesExpr) up = up.parent
    val call = up.parent as? GoCallExpr ?: return null
    return if (call.expression == up) call else null
}

internal fun functionName(owner: PsiElement?): String = when (owner) {
    is GoMethodDeclaration -> "(${owner.receiver?.type?.text ?: "?"}).${owner.name}"
    is GoFunctionDeclaration -> owner.name ?: "?"
    is GoMethodSpec -> (owner.parentOfType<GoTypeSpec>()?.let { "${it.name}." } ?: "") + owner.name
    is GoFunctionLit -> message(
        "context.func.lit",
        owner.parentOfType<GoFunctionOrMethodDeclaration>()?.let(::functionName) ?: message("context.package.level"),
    )
    else -> "?"
}

internal fun calleeName(callee: PsiElement?): String = (callee as? GoNamedElement)?.name ?: "?"

internal fun contextOf(e: PsiElement): String {
    val owner = PsiTreeUtil.getParentOfType(e, GoFunctionLit::class.java, GoFunctionOrMethodDeclaration::class.java)
        ?: e.takeIf { it is GoFunctionOrMethodDeclaration }
    if (owner != null) return functionName(owner)
    e.parentOfType<GoTypeSpec>()?.let { return message("context.type", it.name ?: "?") }
    return (e.containingFile as? GoFile)?.let { message("context.package", it.packageName ?: "?") } ?: ""
}

internal fun locationOf(e: PsiElement): String {
    val file = e.containingFile ?: return ""
    val offset = e.textOffset
    val doc = PsiDocumentManager.getInstance(file.project).getDocument(file)
    val line = if (doc != null && offset <= doc.textLength) doc.getLineNumber(offset) + 1
    else StringUtil.offsetToLineNumber(file.text, offset) + 1
    return "${file.name}:$line"
}

internal fun snippetOf(e: PsiElement): String {
    val text = e.containingFile?.text ?: return e.text.shorten(100)
    val offset = minOf(e.textOffset, text.length)
    val start = text.lastIndexOf('\n', maxOf(0, offset - 1)) + 1
    val end = text.indexOf('\n', offset).takeIf { it >= 0 } ?: text.length
    return text.substring(start, end).trim().shorten(120)
}

internal fun String.shorten(max: Int): String {
    val oneLine = replace(Regex("\\s+"), " ").trim()
    return if (oneLine.length <= max) oneLine else oneLine.take(max - 1) + "…"
}
