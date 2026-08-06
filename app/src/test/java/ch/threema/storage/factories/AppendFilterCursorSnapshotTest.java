package ch.threema.storage.factories;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import androidx.annotation.Nullable;
import ch.threema.app.services.MessageService;
import ch.threema.storage.PageCursor;
import ch.threema.storage.QueryBuilder;
import ch.threema.storage.TimelineKeyset;
import ch.threema.storage.models.MessageType;

/**
 * F1Whisper (second follow-up S2-05): the factory must snapshot the filter's page cursor EXACTLY
 * ONCE per query build. The filter can be backed by a volatile field that another thread replaces
 * at any time — a second {@code getPageCursor()} call mid-build could observe a DIFFERENT cursor
 * and produce a boundary mixing two cursors (the torn-tuple defect, previously possible via the
 * separate id/sort-key filter calls).
 *
 * <p>These tests run the REAL production {@code AbstractMessageModelFactory#appendFilter} against
 * a hostile filter that returns a different cursor object on every call, and record the WHERE
 * clauses through a {@link QueryBuilder} subclass (no database needed — the cursor branch never
 * touches one).</p>
 */
public class AppendFilterCursorSnapshotTest {

    /** Records appended WHERE clauses instead of delegating to the SQLCipher builder. */
    private static final class RecordingQueryBuilder extends QueryBuilder {
        final List<String> wheres = new ArrayList<>();

        @Override
        public void appendWhere(CharSequence inWhere) {
            wheres.add(inWhere.toString());
        }
    }

    /** Neutral filter base; subclasses override the cursor behavior under test. */
    private abstract static class TestFilter implements MessageService.MessageFilter {
        @Override
        public long getPageSize() {
            return 20;
        }

        @Override
        public boolean withStatusMessages() {
            return true;
        }

        @Override
        public boolean withUnsaved() {
            return true;
        }

        @Override
        public boolean onlyUnread() {
            return false;
        }

        @Override
        public boolean onlyDownloaded() {
            return false;
        }

        @Override
        public MessageType[] types() {
            return null;
        }

        @Override
        public int[] contentTypes() {
            return null;
        }

        @Override
        public int[] displayTags() {
            return null;
        }
    }

    private static AbstractMessageModelFactory factory() {
        // The cursor branch of appendFilter never touches the database; the provider is only
        // dereferenced lazily by query methods this test does not call — any access is a bug.
        final ch.threema.storage.DatabaseProvider unusedProvider =
            (ch.threema.storage.DatabaseProvider) java.lang.reflect.Proxy.newProxyInstance(
                AppendFilterCursorSnapshotTest.class.getClassLoader(),
                new Class<?>[]{ch.threema.storage.DatabaseProvider.class},
                (proxy, method, args) -> {
                    throw new UnsupportedOperationException(
                        "appendFilter's cursor branch must not touch the database: " + method.getName());
                });
        return new DistributionListMessageModelFactory(unusedProvider);
    }

    @Test
    public void appendFilterSnapshotsTheCursorExactlyOnceAndIgnoresThePageReferenceId() {
        final AtomicInteger cursorCalls = new AtomicInteger();
        final AtomicInteger referenceIdCalls = new AtomicInteger();
        final MessageService.MessageFilter hostileFilter = new TestFilter() {
            @Override
            public Integer getPageReferenceId() {
                referenceIdCalls.incrementAndGet();
                return 999;
            }

            @Nullable
            @Override
            public PageCursor getPageCursor() {
                // HOSTILE: a different cursor on every call — mimics a concurrently replaced
                // volatile field. Only the FIRST value may influence the query.
                final int call = cursorCalls.incrementAndGet();
                return PageCursor.of(100 + call, call == 1 ? 5000L : 1L);
            }
        };

        final RecordingQueryBuilder queryBuilder = new RecordingQueryBuilder();
        final List<String> placeholders = new ArrayList<>();
        factory().appendFilter(queryBuilder, hostileFilter, placeholders);

        Assert.assertEquals("cursor must be snapshotted exactly once", 1, cursorCalls.get());
        Assert.assertEquals("legacy reference id must not be consulted when a cursor is carried",
            0, referenceIdCalls.get());
        // Boundary derived solely from the first snapshot: id=101, sortKey=5000.
        Assert.assertEquals(Arrays.asList("5000", "5000", "101"), placeholders);
        Assert.assertTrue(queryBuilder.wheres.contains(TimelineKeyset.boundaryWhereClause(5000L)));
    }

    @Test
    public void nullTailCursorSnapshotProducesTheNullBranchBoundary() {
        final AtomicInteger cursorCalls = new AtomicInteger();
        final MessageService.MessageFilter filter = new TestFilter() {
            @Override
            public Integer getPageReferenceId() {
                return 8;
            }

            @Nullable
            @Override
            public PageCursor getPageCursor() {
                cursorCalls.incrementAndGet();
                return PageCursor.of(8, null);
            }
        };

        final RecordingQueryBuilder queryBuilder = new RecordingQueryBuilder();
        final List<String> placeholders = new ArrayList<>();
        factory().appendFilter(queryBuilder, filter, placeholders);

        Assert.assertEquals(1, cursorCalls.get());
        Assert.assertEquals(Arrays.asList("8"), placeholders);
        Assert.assertTrue(queryBuilder.wheres.contains(TimelineKeyset.boundaryWhereClause(null)));
    }

    @Test
    public void noCursorAndNoReferenceIdAppendsNoBoundary() {
        final MessageService.MessageFilter filter = new TestFilter() {
            @Override
            public Integer getPageReferenceId() {
                return null;
            }
        };

        final RecordingQueryBuilder queryBuilder = new RecordingQueryBuilder();
        final List<String> placeholders = new ArrayList<>();
        factory().appendFilter(queryBuilder, filter, placeholders);

        Assert.assertTrue(placeholders.isEmpty());
        for (String where : queryBuilder.wheres) {
            Assert.assertFalse("unexpected boundary clause: " + where,
                where.contains("CAST(? AS INTEGER)"));
        }
    }
}
