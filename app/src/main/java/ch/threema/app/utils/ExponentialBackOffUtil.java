package ch.threema.app.utils;

import org.slf4j.Logger;

import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import androidx.annotation.Nullable;

import static ch.threema.base.utils.LoggingKt.getThreemaLogger;

public class ExponentialBackOffUtil {
    private static final Logger logger = getThreemaLogger("ExponentialBackOffUtil");
    protected final static ExecutorService singleThreadExecutor = Executors.newSingleThreadExecutor();
    private final Random random;

    // Singleton stuff
    private static ExponentialBackOffUtil sInstance = null;

    public static synchronized ExponentialBackOffUtil getInstance() {
        if (sInstance == null) {
            sInstance = new ExponentialBackOffUtil();
        }
        return sInstance;
    }

    private ExponentialBackOffUtil() {
        this.random = new Random();
    }

    /**
     * Run a Runnable in a ExponentialBackoff
     *
     * @param runnable                Method
     * @param exponentialBackOffCount Count of Retries
     */
    public Future<?> run(final BackOffRunnable runnable, final int exponentialBackOffCount, final String messageUid) {
        return singleThreadExecutor.submit(() -> {
            try {
                for (int n = 0; n < exponentialBackOffCount; ++n) {
                    logger.debug("{} Starting backoff run {}", messageUid, n);
                    try {
                        runnable.run(n);
                        //its ok, do not retry
                        return;
                    } catch (Exception e) {
                        logger.warn("Exponential back-off failed", e);
                        if (n >= exponentialBackOffCount - 1) {
                            //last
                            runnable.failed(e);
                        } else {
                            Thread.sleep((2L << n) * 1000L + random.nextInt(1001));
                        }
                    }
                }
            } catch (InterruptedException ex) {
                logger.debug("{} Exponential backoff aborted by user", messageUid);
                runnable.failed(null);
            }
        });
    }

    public interface BackOffRunnable {
        void run(int currentRetry) throws Exception;

        void finished(int currentRetry);

        /**
         * F1Whisper auto-resend: called once when the last attempt failed or the run was aborted.
         *
         * @param cause the exception of the last attempt, or {@code null} if the run was aborted
         */
        void failed(@Nullable Exception cause);
    }
}
