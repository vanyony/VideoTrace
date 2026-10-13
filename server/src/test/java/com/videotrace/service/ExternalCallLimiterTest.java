package com.videotrace.service;

import com.videotrace.exception.ResourceBusyException;
import org.junit.jupiter.api.Test;
import org.redisson.api.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExternalCallLimiterTest {
    @Test void slotsAreBoundedAndClosingTwiceDoesNotLeakCapacity() {
        RedissonClient redis=mock(RedissonClient.class);
        RRateLimiter rate=mock(RRateLimiter.class);
        when(redis.getRateLimiter(anyString())).thenReturn(rate);
        when(rate.tryAcquire()).thenReturn(true);
        ExternalCallLimiter limiter=new ExternalCallLimiter(redis,1,60,1,30,1,120,1);
        var first=limiter.acquire("asr");
        assertThrows(ResourceBusyException.class,() -> limiter.acquire("asr"));
        verify(rate,times(1)).tryAcquire();
        first.close(); first.close();
        try(var second=limiter.acquire("asr")) {
            assertThrows(ResourceBusyException.class,() -> limiter.acquire("asr"));
        }
    }

    @Test void sharedRateDenialAndRedisFailureReleaseLocalSlotAndNeverBypassQuota() {
        RedissonClient redis=mock(RedissonClient.class);
        RRateLimiter rate=mock(RRateLimiter.class);
        when(redis.getRateLimiter(anyString())).thenReturn(rate);
        when(rate.tryAcquire()).thenReturn(false,true);
        ExternalCallLimiter limiter=new ExternalCallLimiter(redis,1,60,1,30,1,120,1);
        assertThrows(ResourceBusyException.class,() -> limiter.acquire("llm"));
        try(var permit=limiter.acquire("llm")) { assertNotNull(permit); }
        when(redis.getRateLimiter(anyString())).thenThrow(new IllegalStateException("Redis down"));
        assertThrows(ResourceBusyException.class,() -> limiter.acquire("llm"));
    }

    @Test void parallelMediaWorkNeverExceedsConfiguredProcessSlots() throws Exception {
        ExternalCallLimiter limiter=new ExternalCallLimiter(mock(RedissonClient.class),1,60,1,30,1,120,2);
        ExecutorService workers=Executors.newFixedThreadPool(12);
        CountDownLatch start=new CountDownLatch(1),admitted=new CountDownLatch(2),attempted=new CountDownLatch(12),release=new CountDownLatch(1);
        AtomicInteger active=new AtomicInteger(),peak=new AtomicInteger(),busy=new AtomicInteger();
        try {
            var futures=new java.util.ArrayList<Future<?>>();
            for(int i=0;i<12;i++) futures.add(workers.submit(() -> {
                try {
                    start.await();
                    try(var permit=limiter.acquire("media")) {
                        peak.accumulateAndGet(active.incrementAndGet(),Math::max);
                        admitted.countDown(); attempted.countDown(); release.await(); active.decrementAndGet();
                    }
                } catch(ResourceBusyException e) { busy.incrementAndGet(); attempted.countDown(); }
                  catch(InterruptedException e) { Thread.currentThread().interrupt(); }
            }));
            start.countDown();
            assertTrue(admitted.await(2,TimeUnit.SECONDS));
            // Hold both permits until every other worker has attempted admission.
            assertTrue(attempted.await(2,TimeUnit.SECONDS));
            assertEquals(10,busy.get());
            release.countDown();
            for(var future:futures) future.get(2,TimeUnit.SECONDS);
            assertEquals(2,peak.get());
        } finally { release.countDown(); workers.shutdownNow(); }
    }
}
