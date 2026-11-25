package io.github.lookandhate.flowtrace.ui

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.tree.TreeUtil
import io.github.lookandhate.flowtrace.analysis.GoFlowAnalyzer
import io.github.lookandhate.flowtrace.model.Direction
import java.awt.Component
import java.awt.Container
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Скриншоты для страницы плагина на Marketplace: окно результата 1280×800 на демонстрационном коде.
 * Запуск: ./gradlew test --tests '*MarketplaceScreenshots*' -Pflowtrace.screenshots.dir=/путь
 * Без свойства тест ничего не пишет.
 */
class MarketplaceScreenshots : BasePlatformTestCase() {
    private val code = """
        package server

        type Config struct {
        	Addr    string
        	Timeout int
        }

        type Store struct{ cfg *Config }
        type Handler struct{ store *Store }
        type Server struct {
        	cfg     *Config
        	handler *Handler
        }

        func Load(path string) (*Config, error) {
        	cfg := &Config{Addr: ":8080", Timeout: 30}
        	return cfg, nil
        }

        func NewStore(cfg *Config) *Store       { return &Store{cfg: cfg} }
        func NewHandler(store *Store) *Handler { return &Handler{store: store} }

        func NewServer(cfg *Config) *Server {
        	store := NewStore(cfg)
        	return &Server{cfg: cfg, handler: NewHandler(store)}
        }

        func (s *Store) Timeout() int { return s.cfg.Timeout }

        func (c *Config) Validate() error { return nil }

        func Run() error {
        	conf, err := Load("config.yaml")
        	if err != nil {
        		return err
        	}
        	if err := conf.Validate(); err != nil {
        		return err
        	}
        	srv := NewServer(conf)
        	_ = srv
        	return nil
        }
    """.trimIndent()

    fun `test render marketplace screenshots`() {
        val dir = System.getProperty("flowtrace.screenshots.dir") ?: return
        myFixture.configureByText("server.go", code)
        val text = myFixture.file.text
        render(text.indexOf("conf, err") + 1, Direction.FORWARD, File(dir, "flowtrace-destinations.png"))
        render(text.indexOf("return s.cfg.Timeout") + "return s.".length + 1, Direction.BACKWARD, File(dir, "flowtrace-origins.png"))
    }

    private fun render(offset: Int, direction: Direction, out: File) {
        val target = GoFlowAnalyzer.findTarget(myFixture.file, offset)!!
        val graph = GoFlowAnalyzer(project, direction).analyze(target)
        val panel = FlowResultPanel(project, graph)
        Disposer.register(testRootDisposable, panel)
        TreeUtil.expandAll(panel.tree)
        panel.setSize(1280, 800)
        layout(panel)
        val image = BufferedImage(1280, 800, BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().also { panel.paint(it) }.dispose()
        ImageIO.write(image, "png", out)
    }

    private fun layout(c: Component) {
        c.doLayout()
        if (c is Container) c.components.forEach(::layout)
    }
}
