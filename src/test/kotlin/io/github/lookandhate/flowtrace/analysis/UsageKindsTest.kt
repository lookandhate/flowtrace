package io.github.lookandhate.flowtrace.analysis

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lookandhate.flowtrace.model.Direction
import io.github.lookandhate.flowtrace.model.FlowEdge
import io.github.lookandhate.flowtrace.model.FlowGraph
import io.github.lookandhate.flowtrace.model.FlowKind

/** Каждое использование переменной — отдельное ребро со своим типом, строкой и выделенным фрагментом. */
class UsageKindsTest : BasePlatformTestCase() {

    private fun trace(code: String, direction: Direction): FlowGraph {
        myFixture.configureByText("main.go", code.trimIndent())
        val target = GoFlowAnalyzer.findTarget(myFixture.file, myFixture.caretOffset)
        assertNotNull(target)
        return GoFlowAnalyzer(project, direction).analyze(target!!)
    }

    /** «тип строка: выделенное» по первому уровню, отсортировано по строке. */
    private fun FlowGraph.firstLevel(): List<String> =
        next(start).sortedWith(compareBy<FlowEdge>({ it.site.line }, { it.kind })).map { it.describe() }

    private fun FlowEdge.describe() = "${kind.name} ${site.line}: ${site.highlighted}"

    fun `test forward splits uses by kind`() {
        val g = trace(
            """
            package main

            type Holder struct{ v *Item }
            type Item struct{ n int }

            func (i *Item) Touch() {}
            func use(it *Item)     {}

            func keep() *Item {
            	it<caret>em := &Item{}
            	item.Touch()
            	use(item)
            	use(item)
            	other := item
            	_ = other
            	h := Holder{v: item}
            	_ = h
            	if item != nil {
            	}
            	item = &Item{n: 2}
            	x, item := 1, &Item{}
            	_ = x
            	return item
            }
            """, Direction.FORWARD,
        )
        assertEquals(
            listOf(
                "METHOD_CALL 11: item",
                "ARGUMENT 12: item",
                "ARGUMENT 13: item",
                "ASSIGNMENT 14: item",
                "FIELD 16: item",
                "OTHER 18: item",
                "WRITE 20: item",
                "WRITE 21: item",
                "RETURN 23: item",
            ),
            g.firstLevel(),
        )
        // Оба вызова use(item) ведут в один и тот же параметр — два ребра, не одно.
        assertEquals(2, g.next(g.start).count { it.kind == FlowKind.ARGUMENT && it.to.title == "parameter it" })
    }

    fun `test backward collects every write including redeclaration and compound`() {
        val g = trace(
            """
            package main

            func f() (int, error) { return 1, nil }

            func main() {
            	a, err := f()
            	b, err := f()
            	err = nil
            	n := 1
            	n += 2
            	_, _, _ = a, b, err
            	_ = n<caret>
            }
            """, Direction.BACKWARD,
        )
        assertEquals(listOf("ASSIGNMENT 9: 1", "ASSIGNMENT 10: 2"), g.firstLevel())

        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("a, b, err") + 6)
        val err = GoFlowAnalyzer(project, Direction.BACKWARD)
            .analyze(GoFlowAnalyzer.findTarget(myFixture.file, myFixture.caretOffset)!!)
        assertEquals(listOf("ASSIGNMENT 6: f()", "ASSIGNMENT 7: f()", "ASSIGNMENT 8: nil"), err.firstLevel())
    }

    fun `test code line of a site`() {
        val g = trace(
            """
            package main

            func use(v int) {}

            func main() {
            	va<caret>l := 1
            	    use(val)
            }
            """, Direction.FORWARD,
        )
        val edge = g.next(g.start).single()
        assertEquals("use(val)", edge.site.text)
        assertEquals("val", edge.site.highlighted)
        assertEquals("main", edge.site.context)
        // Объявление: выделено только имя.
        assertEquals("val := 1", g.start.code.text)
        assertEquals("val", g.start.code.highlighted)
    }

    fun `test first level filter keeps whole subtrees`() {
        val g = trace(
            """
            package main

            type Logger struct{}

            func (l *Logger) Print(s string) {}

            type Repo struct{ log *Logger }

            func NewRepo(log *Logger) *Repo { return &Repo{log: log} }

            func (r *Repo) Save() { r.log.Print("saved") }

            func main() {
            	lo<caret>g := &Logger{}
            	repo := NewRepo(log)
            	_ = repo
            	log.Print("start")
            }
            """, Direction.FORWARD,
        )
        val arguments = g.reachableVia(setOf(FlowKind.ARGUMENT)).map { it.title }
        // Через аргумент первого уровня — вся цепочка, включая вызов метода глубже.
        assertTrue(arguments.toString(), "receiver l" in arguments && "field Repo.log" in arguments)
        assertFalse(arguments.toString(), "call log.Print()" in arguments)

        val calls = g.reachableVia(setOf(FlowKind.METHOD_CALL)).map { it.title }
        assertTrue(calls.toString(), "call log.Print()" in calls)
        assertFalse(calls.toString(), "parameter log" in calls)
    }
}
