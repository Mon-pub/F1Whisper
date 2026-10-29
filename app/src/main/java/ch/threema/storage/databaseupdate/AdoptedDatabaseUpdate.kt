package ch.threema.storage.databaseupdate

/**
 * An upstream database update registered under an F1Whisper version number (see [ch.threema.storage.DatabaseUpdater]).
 *
 * Runs [upstream] unchanged; only the version it is selected and recorded under differs from the upstream number.
 */
internal class AdoptedDatabaseUpdate(
    override val version: Int,
    val upstream: DatabaseUpdate,
) : DatabaseUpdate {
    override fun run() {
        upstream.run()
    }

    override fun getDescription(): String = buildString {
        append("upstream version ${upstream.version}")
        upstream.getDescription()?.let { description ->
            append(": $description")
        }
    }
}
