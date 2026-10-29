package ch.threema.storage

import ch.threema.storage.databaseupdate.AdoptedDatabaseUpdate
import ch.threema.storage.databaseupdate.DatabaseUpdate
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F1Whisper: the database version sequence (see [DatabaseUpdater]).
 *
 * Released databases record 119, 121, 123, 124 or 127; development installs may sit anywhere in 0..127, and an upgraded
 * install anywhere in 90120..90136. For each of them the exact list of updates that runs is pinned here.
 */
class ForkDatabaseVersionSequenceTest {
    private val updater = DatabaseUpdater(appContext = mockk(), database = mockk())

    private val adoptedThenFork = (90120..90128).toList() + (90131..90136).toList()

    @Test
    fun `every legacy version runs every adopted upstream update and then every fork update`() {
        for (oldVersion in 119..127) {
            assertEquals(adoptedThenFork, versionsFrom(oldVersion), "from $oldVersion")
        }
    }

    @Test
    fun `older legacy versions end with the same sequence`() {
        for (oldVersion in 0 until 119) {
            val versions = versionsFrom(oldVersion)
            assertEquals(adoptedThenFork, versions.takeLast(adoptedThenFork.size), "from $oldVersion")
            assertTrue(versions.all { it > oldVersion }, "from $oldVersion")
        }
    }

    @Test
    fun `an upgraded database runs exactly the updates above its version`() {
        for (oldVersion in 90120..90136) {
            assertEquals(adoptedThenFork.filter { it > oldVersion }, versionsFrom(oldVersion), "from $oldVersion")
        }
    }

    @Test
    fun `nothing runs on the current version`() {
        assertEquals(90136, DatabaseUpdater.VERSION)
        assertEquals(emptyList(), versionsFrom(DatabaseUpdater.VERSION))
    }

    @Test
    fun `adopted updates run the upstream update of the matching number`() {
        val adopted = updater.getUpdates(oldVersion = 119).filterIsInstance<AdoptedDatabaseUpdate>()
        assertEquals((120..128).toList(), adopted.map { it.upstream.version })
        assertEquals((90120..90128).toList(), adopted.map { it.version })
        for (update in adopted) {
            assertEquals("DatabaseUpdateToVersion${update.upstream.version}", update.upstream::class.simpleName)
        }
    }

    @Test
    fun `no upstream-native number above 119 is registered directly`() {
        // Such an update would sit below the stored high-water mark of every upgraded database and could never run.
        val versions = versionsFrom(0)
        assertTrue(versions.none { it in 120 until 90000 }, "found ${versions.filter { it in 120 until 90000 }}")
    }

    @Test
    fun `a future update registered above the current version runs on a current database`() {
        val registered = updater.getUpdates(oldVersion = 0).map { update -> { update } } + { fakeUpdate(90137) }

        val selected = DatabaseUpdater.select(registered, oldVersion = DatabaseUpdater.VERSION)

        assertEquals(listOf(90137), selected.map { it.version })
    }

    @Test
    fun `a future update registered below the current version would never run`() {
        // The failure mode the fork sequence exists to prevent, shown on the real selection rule.
        val registered = updater.getUpdates(oldVersion = 0).map { update -> { update } } + { fakeUpdate(129) }

        val selected = DatabaseUpdater.select(registered, oldVersion = DatabaseUpdater.VERSION)

        assertEquals(emptyList(), selected.map { it.version })
    }

    private fun versionsFrom(oldVersion: Int): List<Int> = updater.getUpdates(oldVersion).map { it.version }

    private fun fakeUpdate(version: Int): DatabaseUpdate = object : DatabaseUpdate {
        override fun run() = Unit

        override val version = version
    }
}
