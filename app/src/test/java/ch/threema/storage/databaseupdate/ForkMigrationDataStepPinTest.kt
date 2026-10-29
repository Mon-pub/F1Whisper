package ch.threema.storage.databaseupdate

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Structural pin for the guarded-replay rule of the fork's own database updates (version 90000 and above).
 *
 * Fork updates can run on a database where they already applied, so a data step (UPDATE, INSERT, DELETE, ...) is only
 * allowed where it is tied to a column the same update has just created. Such a step is proven guarded by executing it,
 * in [ForkMigrationGuardedReplayTest]. This pin makes sure no other fork update carries a data step at all: a new one
 * either stays DDL-only or is added to [GUARDED_DATA_STEP_UPDATES] together with replay cases that prove its guard.
 */
class ForkMigrationDataStepPinTest {
    @Test
    fun `fork updates carry no data step outside the replay-tested ones`() {
        val offenders = forkUpdateSources()
            .filterKeys { it !in GUARDED_DATA_STEP_UPDATES }
            .filterValues { source -> hasDataStep(source) }
            .keys
        assertEquals(emptySet(), offenders)
    }

    @Test
    fun `the replay-tested updates still carry their data step`() {
        // If a listed update loses its data step, it should leave the list, so the list never silently widens the exemption.
        val sources = forkUpdateSources()
        for (name in GUARDED_DATA_STEP_UPDATES) {
            val source = sources[name] ?: error("$name is listed but does not exist")
            assertTrue(hasDataStep(source), "$name is listed but has no data step")
        }
    }

    @Test
    fun `the pin sees every fork update`() {
        // Positive control: an empty or partial directory listing would make the first test pass for the wrong reason.
        val expected = (90131..90136).map { "DatabaseUpdateToVersion$it" }.toSet()
        assertTrue(forkUpdateSources().keys.containsAll(expected), "found only ${forkUpdateSources().keys}")
    }

    @Test
    fun `the data step pattern recognises every shape it must`() {
        val shapes = listOf(
            "execSQL(\"UPDATE `t` SET a = 1\")",
            "execSQL(\"insert into t values (1)\")",
            "execSQL(\"DELETE FROM t\")",
            "execSQL(\"REPLACE INTO t VALUES (1)\")",
            "execSQL(\"INSERT OR REPLACE INTO t VALUES (1)\")",
            "sqLiteDatabase.update(\"t\", values, null, null)",
            "sqLiteDatabase.insert(\"t\", null, values)",
            "sqLiteDatabase.insertOrThrow(\"t\", null, values)",
            "sqLiteDatabase.delete(\"t\", null, null)",
            "sqLiteDatabase.replace(\"t\", null, values)",
            "sqLiteDatabase.compileStatement(sql)",
            "sqLiteDatabase.rawExecSQL(sql)",
        )
        for (shape in shapes) {
            assertTrue(hasDataStep(shape), "not recognised: $shape")
        }
        assertTrue(!hasDataStep("// UPDATE in a comment\n/* DELETE */ val x = 1"))
        // A foreign-key action is part of a table definition, not a data step.
        assertTrue(!hasDataStep("REFERENCES `chat_folder`(`id`) ON UPDATE CASCADE ON DELETE CASCADE"))
        assertTrue(hasDataStep("ON DELETE CASCADE\"); execSQL(\"DELETE FROM t"))
    }

    private fun hasDataStep(source: String): Boolean =
        DATA_STEP.containsMatchIn(stripComments(source).replace(FOREIGN_KEY_ACTION, " "))

    private fun forkUpdateSources(): Map<String, String> =
        File(UPDATES_DIRECTORY)
            .listFiles()
            .orEmpty()
            .filter { FORK_UPDATE_FILE.matches(it.name) }
            .associate { it.nameWithoutExtension to it.readText() }

    /** Removes `//` and block comments; good enough for these files, and any leftover only makes the pin stricter. */
    private fun stripComments(source: String): String =
        source
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("""(?m)^\s*//.*$"""), " ")
            .replace(Regex("""\s//[^"\n]*$""", RegexOption.MULTILINE), " ")

    private companion object {
        const val UPDATES_DIRECTORY = "src/main/java/ch/threema/storage/databaseupdate"
        val FORK_UPDATE_FILE = Regex("""DatabaseUpdateToVersion9\d{4}\.kt""")

        /** Fork updates whose data steps run only beside the ALTER that creates their column, proven by the replay test. */
        val GUARDED_DATA_STEP_UPDATES = setOf("DatabaseUpdateToVersion90133", "DatabaseUpdateToVersion90134")

        val FOREIGN_KEY_ACTION = Regex("""\bON\s+(UPDATE|DELETE)\b""", RegexOption.IGNORE_CASE)

        val DATA_STEP = Regex(
            """\b(UPDATE|INSERT|DELETE|REPLACE)\b|\.(update|insert\w*|delete|replace\w*|compileStatement|rawExecSQL)\(""",
            RegexOption.IGNORE_CASE,
        )
    }
}
