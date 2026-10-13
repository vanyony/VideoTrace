package com.videotrace.config;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ThreadPoolConfigTest {
    @Test void fullExecutorRejectsInsteadOfRunningHeavyWorkOnCallerThread() throws Exception {
        var pool=new ThreadPoolConfig().asrExecutor(1,1,0);
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        try {
            pool.submit(() -> { started.countDown(); try { release.await(); }
                catch(InterruptedException e) { Thread.currentThread().interrupt(); } });
            assertTrue(started.await(2,TimeUnit.SECONDS));
            assertThrows(RejectedExecutionException.class,() -> pool.submit(() -> {}));
            assertEquals(1,pool.getPoolSize());
        } finally { release.countDown(); pool.shutdown(); }
    }
    @Test void contradictoryLimitsFailBeforeCreatingExecutorThreads() {
        assertThrows(IllegalArgumentException.class,() -> new ThreadPoolConfig().ocrExecutor(4,2,1));
    }
}
