package io.github.lookandhate.flowtrace.ui

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lookandhate.flowtrace.analysis.GoFlowAnalyzer
import io.github.lookandhate.flowtrace.model.Direction
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class FlowResultPanelTest : BasePlatformTestCase() {

    fun `test panels build and paint`() {
        myFixture.configureByText(
            "main.go",
            """
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
            }
            """.trimIndent(),
        )
        val target = GoFlowAnalyzer.findTarget(myFixture.file, myFixture.caretOffset)!!
        for (direction in Direction.entries) {
            val graph = GoFlowAnalyzer(project, direction).analyze(target)
            val panel = FlowResultPanel(project, graph)
            Disposer.register(testRootDisposable, panel)
            assertTrue(panel.tree.rowCount > 0)

            val graphPanel = panel.graphPanel
            val size = graphPanel.preferredSize
            assertTrue(size.width > 0 && size.height > 0)
            graphPanel.size = size
            val image = BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB)
            image.createGraphics().also { graphPanel.paint(it) }.dispose()
            System.getProperty("flowtrace.png.dir")?.let {
                ImageIO.write(image, "png", File(it, "graph-${direction.name.lowercase()}.png"))
            }
        }
    }
}
