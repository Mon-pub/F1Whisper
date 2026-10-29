package ch.threema.storage

import android.content.Context
import ch.threema.storage.databaseupdate.*
import kotlin.collections.asReversed
import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * Selects the database updates to run for a stored schema version.
 *
 * F1Whisper owns the version sequence: one monotonic number, fork versions are 90000 and above. The fork's early updates
 * used numbers inside upstream's range (121..125, 127) where upstream later shipped different updates, so both sets were
 * moved above it. Every update adopted from upstream is registered under the next free fork number through [adopted]
 * (upstream 120..128 became 90120..90128), and its source file stays unchanged so future upstream merges stay clean.
 * Upstream-native numbers above 119 are never registered directly: the stored version is a single scalar and only updates
 * numerically above it run, so an update registered below the stored high-water mark could never run.
 *
 * On any database recorded below 90000 every adopted upstream update runs first, then every fork update. The fork updates
 * are written to be safe on a database where they already applied (see [DatabaseUpdateToVersion90133]), so the recorded
 * number does not have to describe the schema. The next free number for an adopted update is 90137.
 */
class DatabaseUpdater(
    private val appContext: Context,
    private val database: SQLiteDatabase,
) {
    fun getUpdates(oldVersion: Int): List<DatabaseUpdate> {
        if (oldVersion == VERSION) {
            return emptyList()
        }
        return select(
            updateConstructors = updates.map { updateConstructor -> { updateConstructor.invoke(database, appContext) } },
            oldVersion = oldVersion,
        )
    }

    companion object {
        const val VERSION = 90136

        /**
         * The updates numerically above [oldVersion], in registration order. Constructs them lazily from the end, so only
         * the updates that will run are built. `internal` so the selection rule itself can be tested with a synthetic list.
         */
        internal fun select(updateConstructors: List<() -> DatabaseUpdate>, oldVersion: Int): List<DatabaseUpdate> =
            updateConstructors
                .asReversed()
                .asSequence()
                .map { updateConstructor -> updateConstructor() }
                .takeWhile { update -> oldVersion < update.version }
                .toList()
                .reversed()

        private val updates: List<(SQLiteDatabase, Context) -> DatabaseUpdate>
            get() = listOf(
                update(::DatabaseUpdateToVersion4),
                update(::DatabaseUpdateToVersion6),
                update(::DatabaseUpdateToVersion7),
                update(::DatabaseUpdateToVersion8),
                update(::DatabaseUpdateToVersion9),
                update(::DatabaseUpdateToVersion10),
                update(::DatabaseUpdateToVersion11),
                update(::DatabaseUpdateToVersion12),
                update(::DatabaseUpdateToVersion13),
                update(::DatabaseUpdateToVersion15),
                update(::DatabaseUpdateToVersion16),
                update(::DatabaseUpdateToVersion17),
                update(::DatabaseUpdateToVersion19),
                update(::DatabaseUpdateToVersion20),
                update(::DatabaseUpdateToVersion21),
                update(::DatabaseUpdateToVersion24),
                update(::DatabaseUpdateToVersion25),
                update(::DatabaseUpdateToVersion27),
                update(::DatabaseUpdateToVersion28),
                update(::DatabaseUpdateToVersion32),
                update(::DatabaseUpdateToVersion33),
                update(::DatabaseUpdateToVersion34),
                update(::DatabaseUpdateToVersion35),
                update(::DatabaseUpdateToVersion36),
                update(::DatabaseUpdateToVersion37),
                update(::DatabaseUpdateToVersion38),
                update(::DatabaseUpdateToVersion40),
                update(::DatabaseUpdateToVersion41),
                update(::DatabaseUpdateToVersion44),
                update(::DatabaseUpdateToVersion45),
                update(::DatabaseUpdateToVersion47),
                update(::DatabaseUpdateToVersion49),
                update(::DatabaseUpdateToVersion50),
                update(::DatabaseUpdateToVersion51),
                update(::DatabaseUpdateToVersion52),
                update(::DatabaseUpdateToVersion56),
                update(::DatabaseUpdateToVersion58),
                update(::DatabaseUpdateToVersion59),
                update(::DatabaseUpdateToVersion60),
                update(::DatabaseUpdateToVersion61),
                update(::DatabaseUpdateToVersion62),
                update(::DatabaseUpdateToVersion65),
                update(::DatabaseUpdateToVersion67),
                update(::DatabaseUpdateToVersion68),
                update(::DatabaseUpdateToVersion69),
                update(::DatabaseUpdateToVersion70),
                update(::DatabaseUpdateToVersion71),
                update(::DatabaseUpdateToVersion72),
                update(::DatabaseUpdateToVersion73),
                update(::DatabaseUpdateToVersion74),
                update(::DatabaseUpdateToVersion75),
                update(::DatabaseUpdateToVersion76),
                update(::DatabaseUpdateToVersion77),
                update(::DatabaseUpdateToVersion78),
                update(::DatabaseUpdateToVersion79),
                update(::DatabaseUpdateToVersion80),
                update(::DatabaseUpdateToVersion81),
                update(::DatabaseUpdateToVersion82),
                update(::DatabaseUpdateToVersion83),
                update(::DatabaseUpdateToVersion84),
                update(::DatabaseUpdateToVersion85),
                update(::DatabaseUpdateToVersion86),
                update(::DatabaseUpdateToVersion87),
                update(::DatabaseUpdateToVersion88),
                update(::DatabaseUpdateToVersion89),
                update(::DatabaseUpdateToVersion90),
                update(::DatabaseUpdateToVersion92),
                update(::DatabaseUpdateToVersion93),
                update(::DatabaseUpdateToVersion94),
                update(::DatabaseUpdateToVersion95),
                update(::DatabaseUpdateToVersion96),
                update(::DatabaseUpdateToVersion97),
                update(::DatabaseUpdateToVersion98),
                update(::DatabaseUpdateToVersion99),
                update(::DatabaseUpdateToVersion100),
                update(::DatabaseUpdateToVersion101),
                update(::DatabaseUpdateToVersion102),
                update(::DatabaseUpdateToVersion103),
                update(::DatabaseUpdateToVersion104),
                update(::DatabaseUpdateToVersion105),
                update(::DatabaseUpdateToVersion106),
                update(::DatabaseUpdateToVersion107),
                update(::DatabaseUpdateToVersion108),
                update(::DatabaseUpdateToVersion109),
                update(::DatabaseUpdateToVersion110),
                update(::DatabaseUpdateToVersion111),
                update(::DatabaseUpdateToVersion112),
                update(::DatabaseUpdateToVersion113),
                update(::DatabaseUpdateToVersion114),
                update(::DatabaseUpdateToVersion115),
                update(::DatabaseUpdateToVersion116),
                update(::DatabaseUpdateToVersion117),
                update(::DatabaseUpdateToVersion118),
                update(::DatabaseUpdateToVersion119),
                // Upstream updates from here on are registered under fork version numbers (see the class comment).
                adopted(90120, ::DatabaseUpdateToVersion120),
                adopted(90121, ::DatabaseUpdateToVersion121),
                adopted(90122, ::DatabaseUpdateToVersion122),
                adopted(90123, ::DatabaseUpdateToVersion123),
                adopted(90124, ::DatabaseUpdateToVersion124),
                adopted(90125, ::DatabaseUpdateToVersion125),
                adopted(90126, ::DatabaseUpdateToVersion126),
                adopted(90127, ::DatabaseUpdateToVersion127),
                adopted(90128, ::DatabaseUpdateToVersion128),
                // F1Whisper's own updates.
                update(::DatabaseUpdateToVersion90131),
                update(::DatabaseUpdateToVersion90132),
                update(::DatabaseUpdateToVersion90133),
                update(::DatabaseUpdateToVersion90134),
                update(::DatabaseUpdateToVersion90135),
                update(::DatabaseUpdateToVersion90136),
            )

        private fun update(databaseUpdate: () -> DatabaseUpdate): (SQLiteDatabase, Context) -> DatabaseUpdate =
            { _, _ -> databaseUpdate() }

        private fun update(databaseUpdate: (SQLiteDatabase) -> DatabaseUpdate): (SQLiteDatabase, Context) -> DatabaseUpdate =
            { database, _ -> databaseUpdate(database) }

        private fun update(databaseUpdate: (SQLiteDatabase, Context) -> DatabaseUpdate) = databaseUpdate

        private fun adopted(version: Int, databaseUpdate: () -> DatabaseUpdate): (SQLiteDatabase, Context) -> DatabaseUpdate =
            { _, _ -> AdoptedDatabaseUpdate(version, databaseUpdate()) }

        private fun adopted(version: Int, databaseUpdate: (SQLiteDatabase) -> DatabaseUpdate): (SQLiteDatabase, Context) -> DatabaseUpdate =
            { database, _ -> AdoptedDatabaseUpdate(version, databaseUpdate(database)) }
    }
}
