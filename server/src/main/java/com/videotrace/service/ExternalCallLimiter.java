package com.videotrace.service;

import com.videotrace.exception.ResourceBusyException;
import org.redisson.api.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** Redis controls actual request rate; concurrency permits belong to this worker process. */
@Service
public class ExternalCallLimiter {
    private final RedissonClient redis;
    private final Map<String,Semaphore> slots;
    private final Map<String,Integer> rates;
    public ExternalCallLimiter(RedissonClient redis,
            @Value("${analysis.calls.asr.concurrent:4}") int asrConcurrent,
            @Value("${analysis.calls.asr.per-minute:60}") int asrRate,
            @Value("${analysis.calls.llm.concurrent:4}") int llmConcurrent,
            @Value("${analysis.calls.llm.per-minute:30}") int llmRate,
            @Value("${analysis.calls.embedding.concurrent:4}") int embeddingConcurrent,
            @Value("${analysis.calls.embedding.per-minute:120}") int embeddingRate,
            @Value("${analysis.media.concurrent:2}") int mediaConcurrent) {
        for(int limit:new int[]{asrConcurrent,asrRate,llmConcurrent,llmRate,embeddingConcurrent,embeddingRate,mediaConcurrent})
            if(limit<1) throw new IllegalArgumentException("Resource limits must be positive");
        this.redis=redis;
        slots=Map.of("asr",new Semaphore(asrConcurrent),"llm",new Semaphore(llmConcurrent),
            "embedding",new Semaphore(embeddingConcurrent),"media",new Semaphore(mediaConcurrent));
        rates=Map.of("asr",asrRate,"llm",llmRate,"embedding",embeddingRate);
    }
    public Permit acquire(String kind) {
        Semaphore semaphore=slots.get(kind);
        if(semaphore==null) throw new IllegalArgumentException("Unknown resource kind");
        if(!semaphore.tryAcquire()) throw new ResourceBusyException();
        Permit permit=new Permit(semaphore);
        try {
            if(!"media".equals(kind)) {
                RRateLimiter limiter=redis.getRateLimiter("videotrace:call-rate:"+kind+":"+rates.get(kind));
                limiter.trySetRate(RateType.OVERALL,rates.get(kind),1,RateIntervalUnit.MINUTES);
                if(!limiter.tryAcquire()) throw new ResourceBusyException();
                limiter.expire(java.time.Duration.ofHours(2));
            }
            return permit;
        } catch(RuntimeException e) {
            permit.close();
            if(e instanceof ResourceBusyException) throw e;
            // Failure of the shared quota store must never silently disable service protection.
            throw new ResourceBusyException();
        }
    }
    public static final class Permit implements AutoCloseable {
        private final Semaphore semaphore;
        private final AtomicBoolean open=new AtomicBoolean(true);
        private Permit(Semaphore semaphore) { this.semaphore=semaphore; }
        @Override public void close() { if(open.compareAndSet(true,false)) semaphore.release(); }
    }
}
