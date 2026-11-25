package io.github.lookandhate.flowtrace.ui

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.tree.TreeUtil
import io.github.lookandhate.flowtrace.analysis.GoFlowAnalyzer
import io.github.lookandhate.flowtrace.model.Direction
import io.github.lookandhate.flowtrace.model.FlowGraph
import io.github.lookandhate.flowtrace.model.FlowKind
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JComponent
import javax.swing.tree.DefaultMutableTreeNode

class FlowResultPanelTest : BasePlatformTestCase() {
    private val code = """
        package main

        type Logger struct{}

        func (l *Logger) Print(s string) {}

        type Repo struct{ log *Logger }
        type Service struct{ repo *Repo }

        func NewRepo(log *Logger) *Repo { return &Repo{log: log} }
        func NewService(r *Repo) *Service { return &Service{repo: r} }

        func (r *Repo) Save() { r.log.Print("saved") }
        func (s *Service) Do() { s.repo.Save() }

        func main() {
        	lo<caret>g := &Logger{}
        	svc := NewService(NewRepo(log))
        	svc.Do()
        	log.Print("start")
        	log = &Logger{}
        }
    """.trimIndent()

    private fun graph(direction: Direction): FlowGraph {
        myFixture.configureByText("main.go", code)
        val target = GoFlowAnalyzer.findTarget(myFixture.file, myFixture.caretOffset)!!
        return GoFlowAnalyzer(project, direction).analyze(target)
    }

    private fun DefaultMutableTreeNode.rows(): List<Row> =
        TreeUtil.treeNodeTraverser(this).preOrderDfsTraversal().map { (it as DefaultMutableTreeNode).userObject as Row }.toList()

    private fun CodeRow.describe() = "${code.line}: ${code.text} [${code.highlighted}]"

    fun `test trace tree groups direct uses and shows code lines`() {
        val g = graph(Direction.FORWARD)
        val root = TraceTreeBuilder(g, FlowKind.entries.toSet(), groupByKind = true, "cycle", "cut").buildTrace()
        assertEquals("17: log := &Logger{} [log]", (root.userObject as CodeRow).describe())
        val groups = root.children().toList().map { ((it as DefaultMutableTreeNode).userObject as KindRow).kind }
        assertEquals(listOf(FlowKind.ARGUMENT, FlowKind.METHOD_CALL, FlowKind.WRITE), groups)

        // Аргумент: строка вызова, под ней — объявление параметра, как в Dataflow у IntelliJ.
        val argument = (root.getChildAt(0).getChildAt(0) as DefaultMutableTreeNode)
        assertEquals("18: svc := NewService(NewRepo(log)) [log]", (argument.userObject as CodeRow).describe())
        val param = argument.getChildAt(0) as DefaultMutableTreeNode
        assertEquals("10: func NewRepo(log *Logger) *Repo { return &Repo{log: log} } [log]", (param.userObject as CodeRow).describe())
        // Одна строка кода не повторяется подряд: родитель и потомок всегда на разных строках.
        for (node in TreeUtil.treeNodeTraverser(root).preOrderDfsTraversal().map { it as DefaultMutableTreeNode }) {
            val parent = (node.parent as? DefaultMutableTreeNode)?.userObject as? CodeRow ?: continue
            val child = node.userObject as? CodeRow ?: continue
            assertFalse("${parent.describe()} -> ${child.describe()}", parent.code.sameLine(child.code))
        }
        // Вызов метода в Save: поле → строка вызова → получатель Print, без повторов строки 13.
        val chain = argument.rows().filterIsInstance<CodeRow>().map { it.code.line }
        assertEquals(listOf(18, 10, 7, 13, 5), chain)
    }

    fun `test direct use on the declaration line is shown`() {
        val g = graph(Direction.BACKWARD)
        val root = TraceTreeBuilder(g, FlowKind.entries.toSet(), groupByKind = false, "cycle", "cut").buildTrace()
        val direct = root.children().toList().map { ((it as DefaultMutableTreeNode).userObject as CodeRow).describe() }
        assertEquals(listOf("17: log := &Logger{} [&Logger{}]", "21: log = &Logger{} [&Logger{}]"), direct)
    }

    fun `test rows follow source order and reassignments are not end points`() {
        myFixture.configureByText(
            "main.go",
            """
            package main

            type Item struct{}

            func (i *Item) Touch() {}
            func use(it *Item)     {}

            func main() {
            	it<caret>em := &Item{}
            	use(item)
            	item.Touch()
            	use(item)
            	item = &Item{}
            	item.Touch()
            	use(item)
            }
            """.trimIndent(),
        )
        val target = GoFlowAnalyzer.findTarget(myFixture.file, myFixture.caretOffset)!!
        val g = GoFlowAnalyzer(project, Direction.FORWARD).analyze(target)
        val root = TraceTreeBuilder(g, FlowKind.entries.toSet(), groupByKind = false, "cycle", "cut").buildTrace()
        val lines = root.children().toList().map { ((it as DefaultMutableTreeNode).userObject as CodeRow).code.line }
        assertEquals(listOf(10, 11, 12, 13, 14, 15), lines)

        val grouped = TraceTreeBuilder(g, FlowKind.entries.toSet(), groupByKind = true, "cycle", "cut").buildTrace()
        val arguments = grouped.children().toList().first { ((it as DefaultMutableTreeNode).userObject as KindRow).kind == FlowKind.ARGUMENT }
        assertEquals(listOf(10, 12, 15), arguments.children().toList().map { ((it as DefaultMutableTreeNode).userObject as CodeRow).code.line })

        // Перезапись — не место, куда уходит значение.
        assertTrue(g.terminals().none { it.code.line == 13 })
    }

    fun `test filter keeps only chosen direct uses`() {
        val g = graph(Direction.FORWARD)
        val root = TraceTreeBuilder(g, setOf(FlowKind.WRITE), groupByKind = false, "cycle", "cut").buildTrace()
        val direct = root.children().toList().map { ((it as DefaultMutableTreeNode).userObject as CodeRow).describe() }
        assertEquals(listOf("21: log = &Logger{} [log]"), direct)
    }

    fun `test panels build render and paint`() {
        for (direction in Direction.entries) {
            val g = graph(direction)
            val panel = FlowResultPanel(project, g)
            Disposer.register(testRootDisposable, panel)
            TreeUtil.expandAll(panel.tree)
            // Рендерер вызывается для каждой строки: лексер и выделение не падают.
            for (row in 0 until panel.tree.rowCount) {
                val path = panel.tree.getPathForRow(row)
                panel.tree.cellRenderer.getTreeCellRendererComponent(
                    panel.tree, path.lastPathComponent, false, true, false, row, false,
                )
            }
            panel.groupByKind = false
            panel.kinds = setOf(g.firstLevelKinds().keys.first())
            panel.groupByKind = true
            panel.kinds = g.firstLevelKinds().keys
            TreeUtil.expandAll(panel.tree)

            save(panel.graphPanel, "graph-${direction.name.lowercase()}")
            panel.tree.size = panel.tree.preferredSize
            save(panel.tree, "tree-${direction.name.lowercase()}")
        }
    }

    private fun save(component: JComponent, name: String) {
        val size = component.preferredSize
        assertTrue(size.width > 0 && size.height > 0)
        component.size = size
        val image = BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().also { component.paint(it) }.dispose()
        System.getProperty("flowtrace.png.dir")?.let { ImageIO.write(image, "png", File(it, "$name.png")) }
    }
}
