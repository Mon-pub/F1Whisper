package ch.threema.storage

/**
 * F1Whisper: the tables and columns the app needs after every database upgrade.
 *
 * Checked inside the upgrade transaction, after the last update ran and before the new version is recorded (see
 * [DatabaseOpenHelper.onUpgrade]). If anything is missing the upgrade fails with [FAILURE_MARKER]: the transaction rolls
 * back, the stored version is not advanced, and the data stays intact for a build that can repair it. Since the fork
 * updates repair missing objects themselves, this can only fire on a damaged database or an integration defect.
 *
 * Presence only: types, constraints and data are not validated. Explicit names, no wildcards; keep this list in step with
 * the factories that create these tables.
 */
internal object ForkSchemaPostcondition {
    /** [DatabaseUpdateException.failedDatabaseUpdateVersion] reported when this check fails. */
    const val FAILURE_MARKER = 99_999

    private val PER_MESSAGE = listOf("expiresAtUtc", "expireStartedAtUtc", "disappearingTimerSeconds", "sortAtUtc")
    private val PER_CONVERSATION = listOf("disappearingMessagesTimerSeconds", "peerDisappearingTimerSeconds")

    val REQUIRED_COLUMNS: Map<String, List<String>> = mapOf(
        "chat_folder" to listOf("id", "name", "position", "createdAt"),
        "chat_folder_member" to listOf("folderId", "conversationUid", "createdAt"),
        "message" to PER_MESSAGE,
        "m_group_message" to PER_MESSAGE,
        "distribution_list_message" to PER_MESSAGE,
        "contacts" to PER_CONVERSATION,
        "m_group" to PER_CONVERSATION,
    )

    /** The required `table.column` names that do not exist; a missing table reports all of its columns. */
    fun missing(columnExists: (table: String, column: String) -> Boolean): List<String> =
        REQUIRED_COLUMNS.flatMap { (table, columns) ->
            columns.filterNot { column -> columnExists(table, column) }.map { column -> "$table.$column" }
        }
}
