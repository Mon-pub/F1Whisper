package ch.threema.storage.factories

/**
 * F1Whisper (sixth fork review): the message factories' package-private production SQL and decisions, reachable from
 * tests that live in another package.
 *
 * It exists so those tests execute what SHIPS rather than a copy of it. Nothing here re-implements anything.
 */
object MessageFactoryTestAccess {
    /** The conditional DELETE the three expiry paths run to claim a due row. */
    fun deleteIfStillDueSql(table: String): String = AbstractMessageModelFactory.deleteIfStillDueSql(table)

    /** Whether `createOrUpdate` refuses to insert a model whose row has gone (sixth fork review, F6-01). */
    fun refusesReinsertion(messageId: Int): Boolean = AbstractMessageModelFactory.refusesReinsertion(messageId)

    /** The WHERE clause every full-row save runs, id plus the deletion boundary (eighth fork review, H8-01). */
    fun contentRowWhere(): String = AbstractMessageModelFactory.CONTENT_ROW_WHERE

    /** The WHERE clause both factories' unread count and unread list run (device report 2026-08-06, U-01). */
    fun unreadRowWhere(): String = AbstractMessageModelFactory.UNREAD_ROW_WHERE

    /** "not deleted for everyone", the fragment the unread filter appends (device report 2026-08-06, U-01). */
    fun notSoftDeleted(): String = AbstractMessageModelFactory.NOT_SOFT_DELETED
}
