package app.alfrd.engram.notion

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * JUnit silently skips a `@Test` method that returns a value, and a Kotlin test written as
 * `fun x() = runBlocking { ...; someCall() }` returns whatever its last call returns. Four guard
 * tests were skipped that way before this check existed. Every `@Test` in this package must return
 * `void`, so a test that compiles but never runs fails the build instead.
 */
class NotionTestHygieneTest {

    @Test
    fun `every test in the notion package returns void so none is silently skipped`() {
        val classes = notionTestClasses()
        assertTrue(classes.size >= 8, "found only ${classes.size} test classes; the scan is not seeing the package")

        val offenders = classes.flatMap { cls ->
            cls.declaredMethods
                .filter { it.isAnnotationPresent(Test::class.java) && it.returnType != Void.TYPE }
                .map { "${cls.simpleName}.${it.name}" }
        }
        assertEquals(emptyList<String>(), offenders, "these @Test methods return a value, so JUnit skips them; end the body with Unit or use runBlocking<Unit>")
    }

    private fun notionTestClasses(): List<Class<*>> {
        val loader = Thread.currentThread().contextClassLoader
        val directories = loader.getResources("app/alfrd/engram/notion").toList().map { File(it.toURI()) }.filter { it.isDirectory }
        return directories.flatMap { dir -> dir.listFiles { f -> f.name.endsWith("Test.class") }.orEmpty().toList() }
            .map { Class.forName("app.alfrd.engram.notion." + it.name.removeSuffix(".class"), false, loader) }
            .distinct()
    }
}
