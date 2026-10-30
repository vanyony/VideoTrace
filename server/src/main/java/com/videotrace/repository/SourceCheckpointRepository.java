package com.videotrace.repository;

import com.videotrace.dto.TaskStage;
import com.videotrace.mapper.SourceCheckpointMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;

/** MySQL 保存恢复真源，Redis 只承担热数据读取。 */
@Repository
public class SourceCheckpointRepository {

    private static final Logger log = LoggerFactory.getLogger(SourceCheckpointRepository.class);
    private static final Duration CACHE_TTL = Duration.ofDays(7);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final SourceCheckpointMapper checkpointMapper;

    public SourceCheckpointRepository(StringRedisTemplate redisTemplate,
                                     ObjectMapper objectMapper,
                                     SourceCheckpointMapper checkpointMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.checkpointMapper = checkpointMapper;
    }

    public <T> T read(Long mediaId,
                      String checkpointName,
                      String redisKey,
                      String field,
                      Class<T> type) {
        return read(mediaId, checkpointName, redisKey, field,
                value -> objectMapper.readValue(value, type));
    }

    public <T> T read(Long mediaId,
                      String checkpointName,
                      String redisKey,
                      String field,
                      TypeReference<T> type) {
        return read(mediaId, checkpointName, redisKey, field,
                value -> objectMapper.readValue(value, type));
    }

    public TaskStage readStage(Long mediaId, String checkpointName, String redisKey) {
        try {
            Object cached = redisTemplate.opsForHash().get(redisKey, "stage");
            if (cached != null) {
                TaskStage stage = TaskStage.from(cached.toString());
                if (stage != null) return stage;
                redisTemplate.opsForHash().delete(redisKey, "stage");
            }
        } catch (RuntimeException e) {
            log.warn("agent_checkpoint_stage_cache_read_failed mediaId={}", mediaId, e);
        }
        String persisted = checkpointMapper.findStage(mediaId, checkpointName);
        if (persisted != null) cacheStage(redisKey, persisted);
        return TaskStage.from(persisted);
    }

    @Transactional
    public void write(Long mediaId,
                      String checkpointName,
                      String stageCheckpointName,
                      String redisKey,
                      String field,
                      TaskStage stage,
                      Object value) {
        try {
            com.videotrace.service.TaskExecutionContext.check();
            String payload = objectMapper.writeValueAsString(value);
            // 数据库是恢复真源，缓存失效只影响速度，不影响用户继续任务。
            checkpointMapper.upsert(mediaId, checkpointName, stage.name(), payload);
            checkpointMapper.upsert(mediaId, stageCheckpointName, stage.name(), null);
            afterCommit(() -> cacheField(redisKey, field, payload, stage.name()));
        } catch (Exception e) {
            throw new IllegalStateException("保存 素材 Checkpoint 失败", e);
        }
    }

    @Transactional
    public void writeStandalone(Long mediaId,
                                String checkpointName,
                                String redisKey,
                                String field,
                                TaskStage stage,
                                Object value) {
        try {
            com.videotrace.service.TaskExecutionContext.check();
            String payload = objectMapper.writeValueAsString(value);
            checkpointMapper.upsert(mediaId, checkpointName, stage.name(), payload);
            afterCommit(() -> cacheField(redisKey, field, payload, stage.name()));
        } catch (Exception e) {
            throw new IllegalStateException("保存 素材 Checkpoint 失败", e);
        }
    }

    @Transactional
    public void writeStage(Long mediaId,
                           String checkpointName,
                           String redisKey,
                           TaskStage stage) {
        com.videotrace.service.TaskExecutionContext.check();
        checkpointMapper.upsert(mediaId, checkpointName, stage.name(), null);
        afterCommit(() -> cacheStage(redisKey, stage.name()));
    }

    @Transactional
    public void deleteByPrefix(Long mediaId, String checkpointPrefix) {
        com.videotrace.service.TaskExecutionContext.check();
        checkpointMapper.deleteByPrefix(mediaId, checkpointPrefix);
    }

    @Transactional
    public void delete(Long mediaId, String checkpointName, String redisKey) {
        com.videotrace.service.TaskExecutionContext.check();
        checkpointMapper.delete(mediaId, checkpointName);
        afterCommit(() -> {
            try {
                redisTemplate.delete(redisKey);
            } catch (RuntimeException e) {
                log.warn("agent_checkpoint_cache_delete_failed key={}", redisKey, e);
            }
        });
    }

    @Transactional
    public void deleteByMediaId(Long mediaId) {
        com.videotrace.service.TaskExecutionContext.check();
        checkpointMapper.deleteByMediaId(mediaId);
    }

    private <T> T read(Long mediaId,
                       String checkpointName,
                       String redisKey,
                       String field,
                       JsonReader<T> reader) {
        try {
            Object cached = com.videotrace.service.TaskExecutionContext.active() ? null : redisTemplate.opsForHash().get(redisKey, field);
            if (cached != null) return reader.read(cached.toString());
        } catch (Exception e) {
            log.warn("agent_checkpoint_cache_read_failed key={} field={}", redisKey, field, e);
            evictField(redisKey, field, e);
        }
        try {
            String payload = checkpointMapper.findPayload(mediaId, checkpointName);
            if (payload == null) return null;
            T value = reader.read(payload);
            // A plan/result row records when that particular value was written, not the
            // current workflow stage. Rehydrating an older field must not rewind progress.
            cacheField(redisKey, field, payload, null);
            return value;
        } catch (Exception e) {
            throw new IllegalStateException("读取 素材 Checkpoint 失败: " + checkpointName, e);
        }
    }

    private void cacheField(String key, String field, String payload, String stage) {
        try {
            redisTemplate.opsForHash().put(key, field, payload);
            if (stage != null) redisTemplate.opsForHash().put(key, "stage", stage);
            redisTemplate.expire(key, CACHE_TTL);
        } catch (RuntimeException e) {
            log.warn("agent_checkpoint_cache_write_failed key={} field={}", key, field, e);
            evictFields(key, e, field, "stage");
        }
    }

    private void cacheStage(String key, String stage) {
        try {
            redisTemplate.opsForHash().put(key, "stage", stage);
            redisTemplate.expire(key, CACHE_TTL);
        } catch (RuntimeException e) {
            log.warn("agent_checkpoint_stage_cache_write_failed key={}", key, e);
            evictFields(key, e, "stage");
        }
    }

    private void evictField(String key, String field, Exception originalError) {
        evictFields(key, originalError, field);
    }

    private void evictFields(String key, Exception originalError, String... fields) {
        try {
            redisTemplate.opsForHash().delete(key, (Object[]) fields);
        } catch (RuntimeException cleanupError) {
            originalError.addSuppressed(cleanupError);
        }
    }

    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    @FunctionalInterface
    private interface JsonReader<T> {
        T read(String value) throws Exception;
    }
}
