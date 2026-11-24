package dev.flowtrace.analysis

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.flowtrace.model.Direction
import dev.flowtrace.model.FlowGraph

class GoFlowAnalyzerTest : BasePlatformTestCase() {

    private fun trace(code: String, direction: Direction): FlowGraph {
        myFixture.configureByText("main.go", code.trimIndent())
        val target = GoFlowAnalyzer.findTarget(myFixture.file, myFixture.caretOffset)
        assertNotNull("под курсором нет переменной", target)
        return GoFlowAnalyzer(project, direction).analyze(target!!)
    }

    private val FlowGraph.terminalTitles get() = terminals().map { it.title }.sorted()

    private fun FlowGraph.assertHas(vararg titles: String) {
        val all = nodes.map { it.title }
        titles.forEach { assertTrue("нет узла «$it» в $nodes", it in all) }
    }

    fun `test origin through constructor chain`() {
        val g = trace(
            """
            package main

            type Config struct{ Addr string }
            type Store struct{ cfg *Config }
            type Server struct{ store *Store }

            func NewStore(cfg *Config) *Store { return &Store{cfg: cfg} }
            func NewServer(cfg *Config) *Server { return &Server{store: NewStore(cfg)} }

            func (s *Store) Use() string { c := s.cfg; return c<caret>.Addr }

            func main() {
            	conf := &Config{Addr: ":80"}
            	srv := NewServer(conf)
            	_ = srv
            }
            """, Direction.BACKWARD,
        )
        g.assertHas("поле Store.cfg", "параметр cfg", "переменная conf")
        assertEquals(listOf("Config{Addr: \":80\"}  (значение)"), g.terminalTitles)
    }

    fun `test origin through return and multi value`() {
        val g = trace(
            """
            package main

            type DB struct{}

            func open() (*DB, error) {
            	db := &DB{}
            	return db, nil
            }

            func main() {
            	d, err := open()
            	_ = err
            	_ = d<caret>
            }
            """, Direction.BACKWARD,
        )
        g.assertHas("результат open()", "переменная db")
        assertEquals(listOf("DB{}  (значение)"), g.terminalTitles)
    }

    fun `test origin across assignments and many callers`() {
        val g = trace(
            """
            package main

            func sink(v int) { _ = v<caret> }

            func a() { sink(1) }
            func b() {
            	x := 2
            	x = 3
            	sink(x)
            }
            """, Direction.BACKWARD,
        )
        assertEquals(listOf("1  (значение)", "2  (значение)", "3  (значение)"), g.terminalTitles)
    }

    fun `test origin through interface receiver`() {
        val g = trace(
            """
            package main

            type Runner interface{ Run() }
            type job struct{ n int }

            func (j *job) Run() { _ = j<caret>.n }

            func start(r Runner) { r.Run() }

            func main() { start(&job{n: 1}) }
            """, Direction.BACKWARD,
        )
        g.assertHas("параметр r")
        assertEquals(listOf("job{n: 1}  (значение)"), g.terminalTitles)
    }

    fun `test sinks through layers`() {
        val g = trace(
            """
            package main

            type Logger struct{}

            func (l *Logger) Print(s string) {}

            type Repo struct{ log *Logger }
            type Service struct{ repo *Repo }

            func NewRepo(log *Logger) *Repo { return &Repo{log: log} }
            func NewService(r *Repo) *Service { return &Service{repo: r} }

            func (r *Repo) Save() { r.log.Print("saved") }

            func main() {
            	lo<caret>g := &Logger{}
            	svc := NewService(NewRepo(log))
            	_ = svc
            }
            """, Direction.FORWARD,
        )
        g.assertHas("параметр log", "поле Repo.log", "получатель l")
        // Сам log в svc не попадает: туда уходит *Repo, а log лежит в его поле.
        assertFalse(g.nodes.any { it.title == "переменная svc" })
        assertEquals(listOf("получатель l"), g.terminalTitles)
        // Цепочка проходит через метод, где поле читается и вызывается.
        val contexts = g.pathFromStart(g.terminals().single()).map { it.to.context }
        assertTrue(contexts.toString(), "(*Repo).Save" in contexts)
    }

    fun `test cycle terminates`() {
        val g = trace(
            """
            package main

            func loop(n int) int {
            	if n > 0 {
            		return loop(n - 1)
            	}
            	return n<caret>
            }

            func main() { loop(10) }
            """, Direction.BACKWARD,
        )
        assertTrue(g.terminalTitles.toString(), "10  (значение)" in g.terminalTitles)
    }

    fun `test variadic options do not take lone argument`() {
        val g = trace(
            """
            package main

            type Config struct{}
            type Option func(*Config)

            func New(cfg *Config, opts ...Option) { _ = op<caret>ts }

            func main() {
            	conf := &Config{}
            	New(conf)
            }
            """, Direction.BACKWARD,
        )
        assertFalse(g.nodes.toString(), g.nodes.any { it.title == "переменная conf" })
    }

    fun `test lone multi result argument spreads over parameters`() {
        val g = trace(
            """
            package main

            func pair() (int, string) { return 1, "b" }

            func use(n int, s string) { _ = s<caret> }

            func main() { use(pair()) }
            """, Direction.BACKWARD,
        )
        assertEquals(listOf("\"b\"  (значение)"), g.terminalTitles)
    }

    fun `test no target on keyword`() {
        myFixture.configureByText("main.go", "package main\n\nfu<caret>nc main() {}\n")
        assertNull(GoFlowAnalyzer.findTarget(myFixture.file, myFixture.caretOffset))
    }
}
