package com.videotrace.service;

import com.videotrace.dto.AnalysisTaskMsg;
import com.videotrace.dto.TaskStatus;
import com.videotrace.dto.TaskStage;
import com.videotrace.entity.MediaFile;
import com.videotrace.mapper.MediaFileMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.util.NoSuchElementException;

@Service
public class TranscriptionTaskService {
    private static final Logger log = LoggerFactory.getLogger(TranscriptionTaskService.class);
    private final MediaFileMapper mediaFileMapper;
    private final VideoTranscriptionService videoTranscriptionService;
    private final MediaService mediaService;
    private final StringRedisTemplate redisTemplate;
    private final TaskEventService taskEventService;
    private final AnalysisDeliveryService deliveries;
    private final AnalysisExecutionService executions;

    public TranscriptionTaskService(MediaFileMapper mediaFileMapper,
            VideoTranscriptionService videoTranscriptionService, MediaService mediaService,
            StringRedisTemplate redisTemplate, TaskEventService taskEventService,
            AnalysisDeliveryService deliveries, AnalysisExecutionService executions) {
        this.mediaFileMapper = mediaFileMapper;
        this.videoTranscriptionService = videoTranscriptionService;
        this.mediaService = mediaService;
        this.redisTemplate = redisTemplate;
        this.taskEventService = taskEventService;
        this.deliveries = deliveries;
        this.executions = executions;
    }

    public boolean submit(MediaFile mediaFile) {
        try {
            deliveries.enqueue(mediaFile.getUserId(), new AnalysisTaskMsg(mediaFile.getId(),
                    AnalysisTaskMsg.TRANSCRIBE, mediaFile.getContentHash(), AnalysisTaskMsg.TRANSCRIPTION_GOAL), null);
        } catch (DuplicateKeyException e) {
            return false;
        }
        // The durable task is accepted even if its progress notification cannot be sent.
        notify(mediaFile.getId(), TaskStatus.of(TaskStatus.State.QUEUED, "文字提取任务已持久化"), TaskStage.QUEUED);
        return true;
    }

    /** Runs synchronously inside the durable worker's lease, never in a detached @Async task. */
    public void transcribe(Long mediaId) {
        TaskExecutionContext.check();
        MediaFile mediaFile = mediaFileMapper.selectById(mediaId);
        if (mediaFile == null) throw new NoSuchElementException("视频已删除");
        notify(mediaId, TaskStatus.of(TaskStatus.State.PROCESSING, "正在识别视频语音"), TaskStage.ASR);
        String transcript = videoTranscriptionService.transcribe(mediaService.readableSource(mediaFile.getFilePath()));
        TaskExecutionContext.guarded(() -> {
            MediaFile update = new MediaFile();
            update.setId(mediaId);
            update.setTranscriptText(transcript);
            // Update only the transcript; concurrent analysis and metadata must remain intact.
            if (mediaFileMapper.updateById(update) != 1) throw new NoSuchElementException("视频已删除");
        });
        mediaService.invalidateUserList(mediaFile.getUserId());
    }

    public void publishOutcome(Long mediaId, AnalysisExecutionService.Outcome outcome) {
        if (outcome == AnalysisExecutionService.Outcome.IGNORED) return;
        try {
            MediaFile mediaFile = mediaFileMapper.selectById(mediaId);
            if (mediaFile != null) notify(mediaId, status(mediaFile),
                    outcome == AnalysisExecutionService.Outcome.SUCCEEDED ? TaskStage.COMPLETED
                    : outcome == AnalysisExecutionService.Outcome.FAILED ? TaskStage.FAILED : TaskStage.RETRYING);
        } catch (RuntimeException e) {
            log.warn("transcription_outcome_notification_failed mediaId={}", mediaId, e);
        }
    }

    public TaskStatus status(MediaFile mediaFile) {
        var task = executions.latestTranscription(mediaFile.getId());
        if (task != null) {
            return switch (task.state()) {
                case "QUEUED" -> TaskStatus.of(TaskStatus.State.QUEUED, "文字提取已排队，等待执行或自动重试");
                case "RUNNING" -> TaskStatus.of(TaskStatus.State.PROCESSING, "正在提取文字，执行中断后将自动恢复");
                case "SUCCEEDED" -> TaskStatus.completed(mediaFile.getTranscriptText());
                case "FAILED" -> TaskStatus.of(TaskStatus.State.FAILED, "文字提取失败，请重新提交");
                default -> TaskStatus.of(TaskStatus.State.FAILED, "文字提取任务状态不可用");
            };
        }
        // Legacy in-memory tasks cannot survive process restarts. Never trust their old active flag.
        if (mediaFile.getTranscriptText() != null && !mediaFile.getTranscriptText().isBlank())
            return TaskStatus.completed(mediaFile.getTranscriptText());
        String legacy = redisTemplate.opsForValue().get("transcription:state:" + mediaFile.getId());
        return legacy == null ? TaskStatus.of(TaskStatus.State.NOT_STARTED, "尚未提交文字提取任务")
                : TaskStatus.of(TaskStatus.State.FAILED, "旧文字提取任务已中断，请重新提交");
    }

    private void notify(Long mediaId, TaskStatus status, TaskStage stage) {
        try {
            taskEventService.publishTranscription(mediaId, status, stage);
        } catch (RuntimeException e) {
            log.warn("transcription_notification_failed mediaId={}", mediaId, e);
        }
    }
}
