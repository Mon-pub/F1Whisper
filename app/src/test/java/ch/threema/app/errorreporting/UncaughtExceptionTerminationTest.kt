package ch.threema.app.errorreporting

import android.content.Context
import io.mockk.mockk
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * F1Whisper: pins the invariant that keeps a main-thread crash from turning into a permanent freeze -
 * **the process-wide uncaught exception handler must always end up terminating the process, and nothing may
 * take it away.**
 *
 * **The defect.** `CallActivity.onDestroy()` called `Thread.setDefaultUncaughtExceptionHandler(null)`
 * unconditionally, while nothing in that activity ever installed one. So the first voice or video call in a
 * process silently removed the handler that `ThreemaApplication.onCreate()` installs, for the rest of that
 * process's life. The handler it removed was [ThreemaUncaughtExceptionHandler], which delegates to the
 * platform's `RuntimeInit.KillApplicationHandler` - the thing that actually kills the process. With it gone,
 * `ThreadGroup.uncaughtException` merely prints to `System.err` and returns, so a later throwable on the main
 * thread is not fatal: `ActivityThread.main()` unwinds, ART detaches the main thread inside
 * `DetachCurrentThread` and enters `JII::DestroyJavaVM`, and `ThreadList::WaitForOtherNonDaemonThreadsToExit`
 * blocks because dozens of non-daemon threads are still alive. The result is an app that looks alive - the
 * CSP socket kept echoing for minutes - with a dead UI, no notifications and no dispatched broadcasts, until
 * the system ANRs it. Observed on v6.4.3-39, 2026-09-13.
 *
 * **What is executed and what is pinned.** The delegation is executed, on both the happy path and the path
 * where reporting throws: on a unit-test JVM the "platform default" is simply whatever the test installs, so
 * a fake can record it, and the reporting step is injectable so its failure can be forced. (Without that seam
 * the failure path could not be reached at all: `logger.error` is a no-op here because `LogBackendFactoryImpl`
 * returns no backends under JUnit, and the real reporting call is compiled out on onprem, where
 * `ERROR_REPORTING_SUPPORTED` is a constant `false`.) `CallActivity` is pinned at source only: it needs a real
 * Activity lifecycle plus Koin, and there is no Robolectric in this project.
 *
 * **What the source pins are for.** Everything a behavioural test cannot say about the SHAPE of the method:
 * that no work runs before the protected block, that the delegation is unconditional, and that nowhere else
 * in the app installs a handler that does not terminate. Each pin below exists because a specific edit was
 * shown to pass every behavioural test while restoring the freeze.
 *
 * **Why the source pins earn their keep at the 6.5.2 merge.** Not on `CallActivity`: upstream still carries
 * the bare removal (`6.5.2-1212:CallActivity.java:950`) but has not touched that hunk since 6.4.3, so a
 * three-way merge keeps our deletion with no conflict. It is *this* file's subject that conflicts. Upstream
 * 6.5.2 rewrites `uncaughtException` (`UncaughtExceptionsLogger.logUnhandledException`,
 * `shouldCreateErrorReport`, `ErrorRecordStoreImpl.create`) and still leaves the delegation outside any
 * `finally`. Resolving that conflict by taking upstream's new body would silently drop the guarantee.
 * [the handler delegates to the platform default from a finally block] is what refuses that resolution.
 */
class UncaughtExceptionTerminationTest {

    // -----------------------------------------------------------------------------------------------------
    // 1. Executed: the handler reaches its delegate
    // -----------------------------------------------------------------------------------------------------

    private class RecordingHandler : Thread.UncaughtExceptionHandler {
        val calls = mutableListOf<Pair<Thread, Throwable>>()

        override fun uncaughtException(t: Thread, e: Throwable) {
            calls += t to e
        }
    }

    @Test
    fun `the handler delegates to the default handler that was installed when it was constructed`() {
        val platform = RecordingHandler()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val handler = try {
            // Constructed while `platform` is the process default, which is how ThreemaApplication.onCreate
            // does it: at that moment the default is still RuntimeInit.KillApplicationHandler.
            Thread.setDefaultUncaughtExceptionHandler(platform)
            ThreemaUncaughtExceptionHandler(appContext = mockk<Context>(relaxed = true))
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }

        val thread = Thread.currentThread()
        val throwable = RuntimeException("boom")

        // Whatever the reporting step does, including throwing, the delegation must still have happened:
        // reaching the platform handler is the only thing that ends the process.
        runCatching { handler.uncaughtException(thread, throwable) }

        assertEquals(
            1,
            platform.calls.size,
            "the handler must delegate exactly once to the default handler it captured at construction. " +
                "Without that delegation nothing terminates the process, and a main-thread throwable leaves " +
                "a zombie with a dead looper instead of a crash and a restart",
        )
        val (delegatedThread, delegatedThrowable) = platform.calls.single()
        assertSame(thread, delegatedThread, "the delegate must receive the original thread")
        assertSame(throwable, delegatedThrowable, "the delegate must receive the original throwable")
    }

    @Test
    fun `the handler still delegates when the reporting step itself throws`() {
        // This is the whole point of the finally, and the only assertion here that a source-level pin
        // cannot make honestly: an order check is satisfied by an empty finally with the delegation
        // placed after it, which would silently drop the guarantee again.
        val platform = RecordingHandler()
        var reportingRan = false
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val handler = try {
            Thread.setDefaultUncaughtExceptionHandler(platform)
            ThreemaUncaughtExceptionHandler(
                appContext = mockk<Context>(relaxed = true),
                reportUnhandledException = {
                    reportingRan = true
                    throw OutOfMemoryError("reporting failed while handling a crash")
                },
            )
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }

        val thread = Thread.currentThread()
        val throwable = RuntimeException("boom")

        runCatching { handler.uncaughtException(thread, throwable) }

        assertTrue(
            reportingRan,
            "the reporting step must actually have been called, otherwise this test silently stops " +
                "exercising the failure path it exists for: simply deleting the call would 'pass' it",
        )
        assertEquals(
            1,
            platform.calls.size,
            "when the reporting step throws, the delegation must still happen. Skipping it is exactly the " +
                "failure that freezes the app, and it would be silent: ART discards a throwable raised by " +
                "an uncaught exception handler, so nothing would be logged or reported",
        )
        val (delegatedThread, delegatedThrowable) = platform.calls.single()
        assertSame(thread, delegatedThread, "the delegate must receive the original thread, not the new failure")
        assertSame(
            throwable,
            delegatedThrowable,
            "the delegate must receive the ORIGINAL throwable, not whatever the reporting step threw",
        )
    }

    @Test
    fun `an Error delegates exactly like an Exception`() {
        // The two tests above both hand the handler a RuntimeException, so `if (e is Exception)` around the
        // delegation would pass both while quietly making OutOfMemoryError and StackOverflowError -- the
        // throwables most likely to be the real cause of a crash under memory pressure -- non-fatal.
        // Delegation must be unconditional in the type of the throwable.
        val platform = RecordingHandler()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val handler = try {
            Thread.setDefaultUncaughtExceptionHandler(platform)
            ThreemaUncaughtExceptionHandler(appContext = mockk<Context>(relaxed = true))
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }

        val thread = Thread.currentThread()
        val error = StackOverflowError("deep recursion on main")

        runCatching { handler.uncaughtException(thread, error) }

        assertEquals(
            1,
            platform.calls.size,
            "an Error must reach the platform handler exactly as an Exception does. Nothing about the " +
                "termination guarantee may depend on the type of the throwable",
        )
        assertSame(error, platform.calls.single().second, "the delegate must receive the original Error")
    }

    // -----------------------------------------------------------------------------------------------------
    // 2. Pinned at source: nothing clears the handler
    // -----------------------------------------------------------------------------------------------------

    /**
     * Every production source set of this module, plus every other Gradle module's `src/main`. Unit tests run
     * with the module directory as the working directory, so `src` is `app/src` and `..` is the repo root.
     */
    private fun productionSourceRoots(): List<File> {
        val appSourceSets = File("src")
            .listFiles { file -> file.isDirectory && file.name != "test" && file.name != "androidTest" }
            ?.toList()
            ?: error("app/src did not list - unit tests must run with the app module as working directory")

        val otherModules = File("..")
            .listFiles { file -> file.isDirectory && file.name != "app" && !file.name.startsWith(".") }
            ?.map { module -> File(module, "src/main") }
            ?.filter { it.isDirectory }
            ?: emptyList()

        return appSourceSets + otherModules
    }

    private fun productionSources(): List<File> =
        productionSourceRoots()
            .flatMap { it.walkTopDown().toList() }
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }

    /** `ch/threema/app/Foo.kt`, stable across modules and source sets. */
    private fun File.displayPath(): String =
        path.replace(File.separatorChar, '/').substringAfter("java/")

    @Test
    fun `no production source clears the default uncaught exception handler`() {
        // Whitespace-insensitive, and tolerant of a Java cast or a Kotlin trailing comma, so that a
        // reformat cannot smuggle the call back past this pin.
        val clearsHandler =
            Regex("""setDefaultUncaughtExceptionHandler\((\(Thread\.UncaughtExceptionHandler\))?null,?\)""")

        val offenders = productionSources()
            .filter { clearsHandler.containsMatchIn(it.readText().replace(Regex("""\s+"""), "")) }
            .map { it.displayPath() }
            .sorted()

        assertTrue(
            offenders.isEmpty(),
            "clearing the default uncaught exception handler disarms process termination for the whole " +
                "remaining process lifetime, so the next main-thread throwable freezes the app in " +
                "DestroyJavaVM instead of crashing it. Offending file(s): $offenders",
        )
    }

    @Test
    fun `the only production sources that touch the default handler at all are the two that may`() {
        val touching = productionSources()
            .filter { it.readText().contains("setDefaultUncaughtExceptionHandler") }
            .map { it.displayPath() }
            .sorted()

        assertEquals(
            listOf(
                // installs it once, from Application.onCreate
                "ch/threema/app/ThreemaApplication.kt",
                // deliberately swaps in a swallowing handler, then exitProcess(0) a few lines later
                "ch/threema/app/reset/ResetAppTask.kt",
            ),
            touching,
            "a new site that replaces the process-wide handler must be reviewed against the termination " +
                "guarantee before it is added here",
        )
    }

    @Test
    fun `no production source installs a thread-specific uncaught exception handler`() {
        // A per-thread handler on the main thread produces the identical freeze: Thread.getUncaughtException-
        // Handler() returns it in preference to the ThreadGroup, so a non-terminating one never reaches
        // KillApplicationHandler. None exists today; keep it that way.
        //
        // The method name alone is the match, with no required punctuation, so the call form
        // (`t.setUncaughtExceptionHandler(h)`), a Java method reference (`t::setUncaughtExceptionHandler`)
        // and a bare call inside a Thread subclass are all caught. Requiring a `(` missed the method
        // reference. This cannot collide with the process-wide setter: "setUncaughtExceptionHandler" is not
        // a substring of "setDefaultUncaughtExceptionHandler".
        val installsPerThreadHandler = Regex("""setUncaughtExceptionHandler|\.uncaughtExceptionHandler=""")
        val offenders = productionSources()
            .filter { file ->
                installsPerThreadHandler.containsMatchIn(file.readText().replace(Regex("""\s+"""), ""))
            }
            .map { it.displayPath() }
            .sorted()

        assertTrue(
            offenders.isEmpty(),
            "a thread-specific handler takes precedence over the ThreadGroup and so over the platform " +
                "KillApplicationHandler. If one is ever needed it must terminate the process itself. " +
                "Offending file(s): $offenders",
        )
    }

    // -----------------------------------------------------------------------------------------------------
    // 3. Pinned at source: the delegation survives a throwing reporting step
    // -----------------------------------------------------------------------------------------------------

    @Test
    fun `the handler delegates to the platform default from a finally block`() {
        // Comments are stripped BEFORE brace-matching, not after. A comment containing a stray brace would
        // otherwise close the extracted region early, and that produced a genuine false PASS: `} finally {
        // // { }` followed by the delegation OUTSIDE the block matched as though the delegation were inside
        // it. (A brace inside a string literal can still confuse the matcher. That direction fails loudly
        // with unbalanced braces rather than passing silently, which is the safe way round.)
        val source =
            File("src/main/java/ch/threema/app/errorreporting/ThreemaUncaughtExceptionHandler.kt")
                .readText()
                .stripComments()

        val signature = source.indexOf("override fun uncaughtException(")
        assertTrue(signature >= 0, "uncaughtException must still exist on ThreemaUncaughtExceptionHandler")
        // Brace-match the method itself, so the checks below cannot accidentally reach into a later
        // declaration in the same file.
        val body = braceMatchedBlockAfter(source, source.indexOf(')', signature))

        val tryStart = body.indexOf("try {")
        val finallyStart = body.indexOf("} finally {")

        assertTrue(tryStart >= 0, "the reporting work must sit inside a try block")
        assertTrue(finallyStart > tryStart, "the try block must have a finally")

        // NOTHING may run before the try. Moving a single statement out in front of it -- `logger.error(...)`
        // is the obvious candidate, and an ordinary-looking refactor -- passes every behavioural test above
        // while reintroducing the freeze: if that statement throws, the try is never entered, the finally
        // never runs, and the delegation never happens. Under memory pressure, which is when the handler
        // matters most, that is not hypothetical.
        assertTrue(
            body.trimStart().startsWith("try {"),
            "the method body must begin with the try block. Any statement before it runs unprotected, and " +
                "if it throws the delegation is skipped and the app freezes instead of crashing. Body " +
                "began with: ${body.trimStart().take(120)}",
        )

        // Brace-match the finally, so this checks BLOCK MEMBERSHIP and not merely token order. An order
        // check is satisfied by `} finally { }` with the delegation placed after the block, which drops
        // the guarantee entirely while looking correct.
        val finallyBody = braceMatchedBlockAfter(body, finallyStart + "} finally ".length)

        assertEquals(
            1,
            Regex(Regex.escape("defaultHandler?.uncaughtException(t, e)")).findAll(body).count(),
            "exactly one delegation site in the method",
        )
        // Equality, not containment. The delegation must be the WHOLE of the finally: inside it, and
        // unconditional. Containment alone accepts `if (e is Exception) { ... }` around it, which passes
        // every behavioural test that hands the handler a RuntimeException while quietly making an
        // OutOfMemoryError or StackOverflowError on main non-fatal - and those are the throwables most
        // likely to be the real cause. Block membership rather than token order also refuses
        // `} finally { }` with the delegation moved after the block.
        assertEquals(
            "defaultHandler?.uncaughtException(t, e)",
            finallyBody.trim(),
            "the finally block must contain the delegation to the platform default handler and NOTHING " +
                "else: no condition around it, no work before or after it. It is the only thing that ends " +
                "the process, and Thread::HandleUncaughtExceptions discards anything this handler throws, " +
                "so every way of skipping it fails silently. Finally block was: $finallyBody",
        )
    }

    /**
     * Java and Kotlin line and block comments removed. Deliberately not string-literal aware: it runs on one
     * small file whose content this test also constrains, and the failure direction is loud.
     */
    private fun String.stripComments(): String =
        replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")

    /** Contents of the `{ ... }` block starting at or after [from], brace-matched. */
    private fun braceMatchedBlockAfter(source: String, from: Int): String {
        val open = source.indexOf('{', from)
        require(open >= 0) { "no block found after offset $from" }
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return source.substring(open + 1, index)
                    }
                }
            }
        }
        error("unbalanced braces after offset $from")
    }
}
