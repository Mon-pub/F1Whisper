package ch.threema.storage.factories;

import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

import ch.threema.storage.TimelineKeyset;

/**
 * F1Whisper (fork review H-06, follow-up review P0-6): EXECUTABLE regression tests for the
 * timeline keyset pagination. These do not merely compare SQL strings — they run the production
 * ordering ({@link TimelineKeyset#TIMELINE_ORDER_BY}) and boundary
 * ({@link TimelineKeyset#boundaryWhereClause}/{@link TimelineKeyset#boundaryArgs}) strings as REAL
 * SQL against the REAL {@code message} table definition (executed verbatim from the generated
 * {@code database/schema.sql} snapshot) and page through adversarial datasets:
 *
 * <ul>
 *   <li>delayed high-ID rows carrying an old sender timestamp (the original H-06 hole),</li>
 *   <li>reference-row deletion between page requests (the follow-up P0-6 hole),</li>
 *   <li>equal sort keys (id tiebreak),</li>
 *   <li>legacy NULL sort keys, including a page boundary crossing into the NULL tail.</li>
 * </ul>
 *
 * <p>The cursor is carried as the {@code (effectiveSortKey, id)} tuple of the last returned row —
 * exactly what production {@code MessageFilter.getPageCursor()} callers carry (see
 * {@code ch.threema.storage.PageCursor}; the snapshot-once contract is covered by
 * {@code AppendFilterCursorSnapshotTest}).</p>
 */
public class TimelineKeysetPaginationTest {

    private static Connection connection;

    @BeforeClass
    public static void openDatabase() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement statement = connection.createStatement()) {
            statement.execute(loadMessageTableCreateStatement());
        }
    }

    @AfterClass
    public static void closeDatabase() throws Exception {
        if (connection != null) {
            connection.close();
        }
    }

    /** The REAL `message` CREATE TABLE statement from the generated schema snapshot. */
    private static String loadMessageTableCreateStatement() throws Exception {
        try (InputStream in = Objects.requireNonNull(
            TimelineKeysetPaginationTest.class.getResourceAsStream("/database/schema.sql"),
            "schema.sql test resource missing");
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("CREATE TABLE `message`(")) {
                    return line;
                }
            }
        }
        throw new AssertionError("CREATE TABLE `message` not found in schema.sql");
    }

    // --- dataset helpers ---------------------------------------------------------------------

    private void clearTable() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM message");
        }
    }

    private void insertRow(int id, boolean outbox, Long createdAt, Long postedAt, Long sortAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO message (id, outbox, createdAtUtc, postedAtUtc, sortAtUtc, isStatusMessage, isSaved) "
                + "VALUES (?, ?, ?, ?, ?, 0, 1)")) {
            statement.setInt(1, id);
            statement.setInt(2, outbox ? 1 : 0);
            statement.setObject(3, createdAt);
            statement.setObject(4, postedAt);
            statement.setObject(5, sortAt);
            statement.executeUpdate();
        }
    }

    /**
     * The adversarial base dataset. Expected timeline order (newest first, NULL keys last):
     * id9(9000), id5(4000), id4(4000), id3(2500), id2(2000), id6(1500), id1(1000), id8(NULL), id7(NULL).
     */
    private void insertBaseDataset() throws SQLException {
        insertRow(1, false, 1000L, 1000L, 1000L);
        insertRow(2, true, 2000L, 5000L, 2000L);   // outgoing: key = createdAt, NOT the newer postedAt
        insertRow(3, false, 3000L, 2500L, 2500L);  // incoming: key = sender time
        insertRow(4, false, 4000L, null, null);    // pre-v124 row: CASE fallback -> COALESCE(posted, created) = 4000
        insertRow(5, true, 4000L, null, 4000L);    // EQUAL key with id4 -> id tiebreak (id5 first)
        insertRow(6, false, 9000L, 1500L, 1500L);  // delayed high-ID row with OLD sender timestamp
        insertRow(7, false, null, null, null);     // legacy NULL-key tail
        insertRow(8, false, null, null, null);     // legacy NULL-key tail
        insertRow(9, false, 9000L, 9000L, 9000L);
    }

    private static final List<Integer> BASE_ORDER = List.of(9, 5, 4, 3, 2, 6, 1, 8, 7);

    // --- query helpers running the PRODUCTION SQL strings ------------------------------------

    private static final class Row {
        final int id;
        final Long effectiveSortKey;

        Row(int id, Long effectiveSortKey) {
            this.id = id;
            this.effectiveSortKey = effectiveSortKey;
        }
    }

    private List<Row> queryPage(Long referenceSortKey, Integer referenceId, int pageSize) throws SQLException {
        final StringBuilder sql = new StringBuilder("SELECT id, ")
            .append(TimelineKeyset.EFFECTIVE_SORT_KEY_EXPR)
            .append(" FROM message WHERE isStatusMessage=0");
        final List<String> args = new ArrayList<>();
        if (referenceId != null) {
            sql.append(" AND ").append(TimelineKeyset.boundaryWhereClause(referenceSortKey));
            for (String arg : TimelineKeyset.boundaryArgs(referenceSortKey, referenceId)) {
                args.add(arg);
            }
        }
        sql.append(" ORDER BY ").append(TimelineKeyset.TIMELINE_ORDER_BY)
            .append(" LIMIT ").append(pageSize);

        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < args.size(); i++) {
                statement.setString(i + 1, args.get(i));
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                final List<Row> rows = new ArrayList<>();
                while (resultSet.next()) {
                    final long key = resultSet.getLong(2);
                    // wasNull() reports on the MOST RECENT read — capture it before touching
                    // another column (reading id first turned NULL keys into 0, which made the
                    // cursor re-admit the same tail row forever).
                    final boolean keyIsNull = resultSet.wasNull();
                    rows.add(new Row(resultSet.getInt(1), keyIsNull ? null : key));
                }
                return rows;
            }
        }
    }

    /** Paginate to exhaustion, carrying the (key, id) tuple of each page's last row. */
    private List<Integer> paginate(int pageSize) throws SQLException {
        final List<Integer> ids = new ArrayList<>();
        Long referenceSortKey = null;
        Integer referenceId = null;
        int pages = 0;
        while (true) {
            // A broken boundary loops instead of terminating — fail loudly, never hang the suite.
            Assert.assertTrue("pagination did not terminate (cursor no longer advances)", ++pages <= 100);
            final List<Row> page = queryPage(referenceSortKey, referenceId, pageSize);
            if (page.isEmpty()) {
                return ids;
            }
            for (Row row : page) {
                ids.add(row.id);
            }
            final Row last = page.get(page.size() - 1);
            referenceSortKey = last.effectiveSortKey;
            referenceId = last.id;
        }
    }

    private List<Integer> singleOrderedQuery() throws SQLException {
        return rowIds(queryPage(null, null, Integer.MAX_VALUE));
    }

    // --- tests --------------------------------------------------------------------------------

    @Test
    public void fullPaginationWalkEqualsSingleOrderedQueryForEveryPageSize() throws SQLException {
        clearTable();
        insertBaseDataset();
        Assert.assertEquals(BASE_ORDER, singleOrderedQuery());
        for (int pageSize = 1; pageSize <= BASE_ORDER.size() + 1; pageSize++) {
            Assert.assertEquals("page size " + pageSize, BASE_ORDER, paginate(pageSize));
        }
    }

    @Test
    public void equalSortKeysTieBreakByIdDescending() throws SQLException {
        clearTable();
        insertBaseDataset();
        final List<Integer> walk = paginate(1);
        Assert.assertTrue(walk.indexOf(5) < walk.indexOf(4));
    }

    @Test
    public void boundaryCrossingIntoNullTailAdmitsNoDuplicatesOrOmissions() throws SQLException {
        clearTable();
        insertBaseDataset();
        // Page size 2 makes page 4 = [id1(1000), id8(NULL)]: the cursor lands ON a NULL-key row.
        // Without the dedicated NULL-reference branch, the next page would re-admit the whole tail.
        final List<Integer> walk = paginate(2);
        Assert.assertEquals(BASE_ORDER, walk);
        Assert.assertEquals("duplicates admitted", walk.size(), new HashSet<>(walk).size());
    }

    @Test
    public void nullReferenceKeyContinuesInsideTheTailOnly() throws SQLException {
        clearTable();
        insertBaseDataset();
        // Cursor at id8 (NULL key): the continuation must be exactly the remaining tail row.
        final List<Row> page = queryPage(null, 8, 10);
        Assert.assertEquals(1, page.size());
        Assert.assertEquals(7, page.get(0).id);
        Assert.assertNull(page.get(0).effectiveSortKey);
    }

    @Test
    public void delayedHighIdRowWithOldSenderTimestampIsReachedExactlyOnce() throws SQLException {
        clearTable();
        insertBaseDataset();
        // Load page one, THEN a reconnect-backlog row arrives: highest id so far, old sender time.
        final List<Row> pageOne = queryPage(null, null, 2); // [id9, id5]
        Assert.assertEquals(List.of(9, 5), rowIds(pageOne));
        insertRow(20, false, 9999L, 1800L, 1800L);

        final List<Integer> ids = new ArrayList<>(rowIds(pageOne));
        Row cursor = pageOne.get(pageOne.size() - 1);
        int pages = 0;
        while (true) {
            Assert.assertTrue("pagination did not terminate", ++pages <= 100);
            final List<Row> page = queryPage(cursor.effectiveSortKey, cursor.id, 2);
            if (page.isEmpty()) {
                break;
            }
            ids.addAll(rowIds(page));
            cursor = page.get(page.size() - 1);
        }
        // The late row lands between id2 (2000) and id6 (1500) — reachable, exactly once. The old
        // id-only cursor ("id < 5") would have excluded id20 from every later page forever.
        Assert.assertEquals(List.of(9, 5, 4, 3, 2, 20, 6, 1, 8, 7), ids);
    }

    @Test
    public void referenceRowDeletionBetweenPagesDoesNotChangeTheContinuation() throws SQLException {
        clearTable();
        insertBaseDataset();
        final List<Row> pageOne = queryPage(null, null, 3); // [id9, id5, id4]
        final Row cursor = pageOne.get(pageOne.size() - 1);
        // The continuation expected while the reference row still exists...
        final List<Integer> continuationBefore = rowIds(queryPage(cursor.effectiveSortKey, cursor.id, 10));
        // ...must be IDENTICAL after the reference row is deleted (the tuple travels with the
        // cursor; nothing is re-read from the vanished row).
        try (Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM message WHERE id=" + cursor.id);
        }
        final List<Integer> continuationAfter = rowIds(queryPage(cursor.effectiveSortKey, cursor.id, 10));
        Assert.assertEquals(continuationBefore, continuationAfter);
        Assert.assertEquals(List.of(3, 2, 6, 1, 8, 7), continuationAfter);
    }

    @Test
    public void orderingAndBoundaryShareTheSameExpression() {
        // Lockstep pin at the string level (kept from the original test): the ORDER BY tuple and
        // the boundary predicate must be built over the exact same expression.
        Assert.assertTrue(TimelineKeyset.TIMELINE_ORDER_BY.startsWith(TimelineKeyset.EFFECTIVE_SORT_KEY_EXPR + " DESC"));
        Assert.assertTrue(TimelineKeyset.TIMELINE_ORDER_BY.endsWith("id DESC"));
        Assert.assertTrue(TimelineKeyset.boundaryWhereClause(1L).contains(TimelineKeyset.EFFECTIVE_SORT_KEY_EXPR + "<CAST(? AS INTEGER)"));
        Assert.assertTrue(TimelineKeyset.boundaryWhereClause(null).contains(TimelineKeyset.EFFECTIVE_SORT_KEY_EXPR + " IS NULL"));
        Assert.assertEquals(3, TimelineKeyset.boundaryArgs(1L, 2).length);
        Assert.assertEquals(1, TimelineKeyset.boundaryArgs(null, 2).length);
    }

    @Test
    public void factoryAliasesPointAtTheSingleDefinition() {
        Assert.assertSame(TimelineKeyset.EFFECTIVE_SORT_KEY_EXPR, AbstractMessageModelFactory.EFFECTIVE_SORT_KEY_EXPR);
        Assert.assertSame(TimelineKeyset.TIMELINE_ORDER_BY, AbstractMessageModelFactory.TIMELINE_ORDER_BY);
    }

    private static List<Integer> rowIds(List<Row> rows) {
        final List<Integer> ids = new ArrayList<>();
        for (Row row : rows) {
            ids.add(row.id);
        }
        return ids;
    }
}
