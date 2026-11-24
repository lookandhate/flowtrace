package io.github.lookandhate.flowtrace

import junit.framework.TestCase
import java.util.Properties

/** Переводы не отстают от английского bundle: те же ключи и те же параметры {0}, {1}. */
class FlowTraceBundleTest : TestCase() {

    private fun load(name: String) = Properties().apply {
        FlowTraceBundle::class.java.classLoader.getResourceAsStream("messages/$name.properties")!!.reader(Charsets.UTF_8).use(::load)
    }

    private fun params(s: String) = Regex("\\{\\d+}").findAll(s).map { it.value }.toSortedSet()

    fun `test russian bundle matches english keys and parameters`() {
        val en = load("FlowTraceBundle")
        val ru = load("FlowTraceBundle_ru")
        assertEquals(en.stringPropertyNames(), ru.stringPropertyNames())
        for (key in en.stringPropertyNames()) {
            assertEquals(key, params(en.getProperty(key)), params(ru.getProperty(key)))
        }
    }
}
