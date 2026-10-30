package com.videotrace.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.videotrace.dto.ProcessingScope;
import com.videotrace.dto.ProcessingTaskMsg;
import com.videotrace.dto.TaskStatus;
import com.videotrace.dto.TaskStage;
import com.videotrace.entity.FailedProcessingTask;
import com.videotrace.mapper.FailedProcessingTaskMapper;
import com.videotrace.utils.ProcessingTaskKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.regex.Pattern;

@Service
public class FailedProcessingTaskService {

    private static final Logger log = LoggerFactory.getLogger(FailedProcessingTaskService.class);
    private static final Duration ACTIVE_TTL = Duration.ofHours(6);
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_REQUEUED = "REQUEUED";
    /** 毒消息可能连 mediaId 都没有，用哨兵值占位，保证台账可写、管理台可见。 */
    private static final long UNKNOWN_MEDIA_ID = -1L;
    private static final Pattern BEARER_SECRET = Pattern.compile(
            "(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]{8,}");
    private static final Pattern NAMED_SECRET = Pattern.compile(
            "(?i)((?:api[-_ ]?key|token|secret)\\s*[=:]\\s*)[^\\s,;]{8,}");
    private static final Pattern PREFIXED_SECRET = Pattern.compile(
            "(?i)sk-[A-Za-z0-9_-]{16,}");

    private final FailedProcessingTaskMapper taskMapper;
    private final ProcessingDeliveryService deliveryService;
    private final com.videotrace.mapper.MediaFileMapper mediaMapper;
    private final StringRedisTemplate redisTemplate;
    private final TaskEventService taskEventService;

    public FailedProcessingTaskService(FailedProcessingTaskMapper taskMapper,
                                     ProcessingDeliveryService deliveryService,
                                     com.videotrace.mapper.MediaFileMapper mediaMapper,
                                     StringRedisTemplate redisTemplate,
                                     TaskEventService taskEventService) {
        this.taskMapper = taskMapper;
        this.deliveryService = deliveryService;
        this.mediaMapper = mediaMapper;
        this.redisTemplate = redisTemplate;
        this.taskEventService = taskEventService;
    }

    public void record(ProcessingTaskMsg message, long attempts, Throwable error) {
        Throwable root = rootCause(error);
        FailedProcessingTask task = new FailedProcessingTask();
        // 台账各列都是 NOT NULL，而毒消息恰恰可能缺字段、或 goal 超过列宽。
        // 这里补占位值并按列宽截断：写台账本身失败的话，失败任务就彻底没有排查抓手了。
        task.setMediaId(message.getMediaId() == null ? UNKNOWN_MEDIA_ID : message.getMediaId());
        task.setAction(column(message.getAction(), "UNKNOWN", 32));
        task.setMode(ProcessingScope.fromNullable(message.getMode()).name());
        task.setContentHash(column(message.getContentHash(), "unknown", 128));
        task.setUserGoal(column(message.getUserGoal(), "(消息缺少分析目标)", 500));
        task.setAttemptCount((int) attempts);
        task.setErrorType(column(root.getClass().getSimpleName(), "UnknownError", 128));
        task.setErrorMessage(sanitizeError(root.getMessage()));
        task.setStatus(STATUS_FAILED);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.insert(task);
    }

    /** 判断台账记录是否由毒消息占位生成——这类记录没有可用于重投的原始参数。 */
    private boolean isPlaceholderRecord(FailedProcessingTask task) {
        return task.getMediaId() == null
                || task.getMediaId() == UNKNOWN_MEDIA_ID
                || !ProcessingTaskMsg.isSupportedAction(task.getAction());
    }

    /** 补齐 NOT NULL 列并截断到列宽，避免台账写入因缺字段或超长而失败。 */
    private String column(String value, String fallback, int maxLength) {
        String text = value == null || value.isBlank() ? fallback : value;
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    public List<FailedProcessingTask> latest() {
        return taskMapper.selectList(new QueryWrapper<FailedProcessingTask>()
                .orderByDesc("id")
                .last("LIMIT 100"));
    }

    public void replay(Long id) {
        FailedProcessingTask task = taskMapper.selectById(id);
        if (task == null) throw new NoSuchElementException("失败任务不存在");
        if (!STATUS_FAILED.equals(task.getStatus())) {
            throw new IllegalArgumentException("该失败任务已经重放");
        }
        // 毒消息台账里的字段是占位值，原样重投必然再次被判为非法消息，
        // 而记录会因此被置成 REQUEUED 再也无法重放。这里直接拒绝并保留 FAILED 状态。
        if (isPlaceholderRecord(task)) {
            throw new IllegalArgumentException("该记录来自非法任务消息，缺少可重放的原始参数");
        }

        com.videotrace.entity.MediaFile media = mediaMapper.selectById(task.getMediaId());
        if (media == null) throw new NoSuchElementException("视频已删除，不能重放任务");
        deliveryService.enqueue(media.getUserId(), new ProcessingTaskMsg(task.getMediaId(),
                task.getAction(), task.getContentHash(), task.getUserGoal()), null);
        task.setStatus(STATUS_REQUEUED);
        task.setUpdatedAt(LocalDateTime.now());
        if (taskMapper.updateById(task) != 1) throw new IllegalStateException("失败任务重放台账更新失败");
    }

    private Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength);
    }

    private String sanitizeError(String value) {
        if (value == null) return null;
        String sanitized = BEARER_SECRET.matcher(value).replaceAll("$1****");
        sanitized = NAMED_SECRET.matcher(sanitized).replaceAll("$1****");
        sanitized = PREFIXED_SECRET.matcher(sanitized).replaceAll("****");
        return truncate(sanitized, 1_000);
    }
}
