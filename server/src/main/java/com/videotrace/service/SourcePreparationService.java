package com.videotrace.service;

import com.videotrace.dto.*;
import com.videotrace.utils.ProcessingTaskKeys;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.NoSuchElementException;

/** Durable preparation of reusable source evidence for the creation workspace. */
@Service
public class SourcePreparationService {
    private final MediaService media;
    private final SourceCheckpointService checkpoints;
    private final VideoContextService builder;
    private final SourceRetrievalService retrieval;
    private final ProcessingDeliveryService deliveries;
    private final ProcessingExecutionService executions;
    private final ProcessingTelemetry telemetry;
    private final RedissonClient locks;
    private final StringRedisTemplate redis;

    public SourcePreparationService(MediaService media,SourceCheckpointService checkpoints,
            VideoContextService builder,SourceRetrievalService retrieval,ProcessingDeliveryService deliveries,
            ProcessingExecutionService executions,ProcessingTelemetry telemetry,RedissonClient locks,StringRedisTemplate redis) {
        this.media=media; this.checkpoints=checkpoints; this.builder=builder; this.retrieval=retrieval;
        this.deliveries=deliveries; this.executions=executions; this.telemetry=telemetry; this.locks=locks; this.redis=redis;
    }
    private String goal() { return ProcessingTaskMsg.PREPARATION_GOAL+":"+builder.parseVersion(); }

    public TaskStatus submit(long owner,long mediaId) {
        media.requireOwnedMedia(mediaId,owner);
        TaskStatus current=status(owner,mediaId);
        if(current.state()==TaskStatus.State.COMPLETED || current.state()==TaskStatus.State.QUEUED || current.state()==TaskStatus.State.PROCESSING) return current;
        try {
            deliveries.enqueue(owner,new ProcessingTaskMsg(mediaId,ProcessingTaskMsg.PREPARE_SOURCE,media.contentHash(mediaId),goal()),null);
        } catch(DuplicateKeyException e) { return status(owner,mediaId); }
        return TaskStatus.of(TaskStatus.State.QUEUED,"素材准备已排队");
    }

    public TaskStatus status(long owner,long mediaId) {
        media.requireOwnedMedia(mediaId,owner);
        var task=executions.latest(mediaId,goal(),ProcessingScope.GENERAL);
        if(task!=null) {
            if("QUEUED".equals(task.state())) return TaskStatus.of(TaskStatus.State.QUEUED,"素材准备已排队，等待执行或自动重试");
            if("RUNNING".equals(task.state())) return TaskStatus.of(TaskStatus.State.PROCESSING,"正在提取语音、画面文字并建立证据索引");
            if("FAILED".equals(task.state())) return TaskStatus.of(TaskStatus.State.FAILED,"素材准备失败，可重试并复用已完成阶段");
        }
        if(CreationService.ready(checkpoints.loadContext(mediaId))) return TaskStatus.completed("素材已就绪");
        return TaskStatus.of(TaskStatus.State.NOT_STARTED,"请准备原视频素材");
    }

    public void prepare(long mediaId) {
        var task=executions.latest(mediaId,goal(),ProcessingScope.GENERAL);
        if(task==null) throw new NoSuchElementException("素材准备任务不存在");
        var source=media.requireOwnedMedia(mediaId,task.userId());
        String hash=ProcessingTaskKeys.normalizeContentHash(mediaId,media.contentHash(mediaId))+":"+builder.parseVersion();
        var lock=locks.getLock(ProcessingTaskKeys.contextLock(hash));
        if(!lock.tryLock()) throw new com.videotrace.exception.ResourceBusyException();
        String trace=null;
        try {
            trace=telemetry.start(mediaId,goal());
            VideoContext context=checkpoints.loadContext(mediaId);
            if(context==null) {
                String value=redis.opsForValue().get(ProcessingTaskKeys.contextOwner(hash));
                Long previous=null;
                try { if(value!=null) previous=Long.valueOf(value); } catch(NumberFormatException ignored) { }
                if(previous!=null && previous.longValue()!=mediaId && media.exists(previous) && checkpoints.loadContext(previous)!=null)
                    builder.adoptStages(previous,mediaId,source.getFilePath());
                context=builder.build(mediaId,source.getFilePath(),"",trace);
                checkpoints.saveContext(mediaId,context);
                redis.opsForValue().set(ProcessingTaskKeys.contextOwner(hash),String.valueOf(mediaId),Duration.ofDays(7));
            }
            if(!CreationService.ready(context)) throw new IllegalArgumentException("原视频未识别到可用于创作的语音或画面文字");
            retrieval.prepare(mediaId,context);
            TaskExecutionContext.check();
            if(!media.exists(mediaId)) { media.purgeRuntimeArtifacts(mediaId); throw new NoSuchElementException("原视频已删除"); }
        } finally {
            try { if(trace!=null) telemetry.flush(trace); }
            finally {
                telemetry.clear();
                if(lock.isHeldByCurrentThread()) lock.unlock();
            }
        }
    }
}
