package com.videotrace.service;

import com.videotrace.dto.TaskStage;
import com.videotrace.dto.VideoChunk;
import com.videotrace.dto.VideoContext;
import com.videotrace.repository.SourceCheckpointRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Durable source context and chunk storage; legacy keys stay compatible. */
@Service
public class SourceCheckpointService {

    private static final Logger log = LoggerFactory.getLogger(SourceCheckpointService.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final SourceCheckpointRepository checkpointRepository;
    private final String parseVersion;

    public SourceCheckpointService(StringRedisTemplate redisTemplate,
                                  ObjectMapper objectMapper,
                                  SourceCheckpointRepository checkpointRepository,
                                  @org.springframework.beans.factory.annotation.Value("${video.parse.version:v1}") String parseVersion) {
        if (!parseVersion.matches("[A-Za-z0-9_-]{1,32}")) throw new IllegalArgumentException("Invalid parse version");
        this.parseVersion = parseVersion;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.checkpointRepository = checkpointRepository;
    }

    public VideoContext loadContext(Long mediaId) {
        return checkpointRepository.read(mediaId, mediaCheckpoint("context"), checkpointKey(mediaId),
                "context", VideoContext.class);
    }

    public List<VideoChunk> loadChunks(Long mediaId) {
        return checkpointRepository.read(
                mediaId,
                mediaCheckpoint("chunks"),
                checkpointKey(mediaId),
                "chunks",
                new TypeReference<List<VideoChunk>>() { });
    }

    public void saveContext(Long mediaId, VideoContext context) {
        VideoContext reusableContext = new VideoContext(context.source(), "", context.segments());
        TaskExecutionContext.guarded(() -> checkpointRepository.write(mediaId, mediaCheckpoint("context"), mediaCheckpoint("stage"),
                checkpointKey(mediaId), "context", TaskStage.CONTEXT_COMPLETED, reusableContext));
    }

    public void saveChunks(Long mediaId, List<VideoChunk> chunks) {
        TaskExecutionContext.guarded(() -> checkpointRepository.write(mediaId, mediaCheckpoint("chunks"), mediaCheckpoint("stage"),
                checkpointKey(mediaId), "chunks", TaskStage.CHUNKS_COMPLETED, List.copyOf(chunks)));
    }

    public void deleteMedia(Long mediaId) {
        checkpointRepository.deleteByMediaId(mediaId);
        try {
            Set<String> goalKeys = redisTemplate.opsForSet().members(goalIndexKey(mediaId));
            List<String> keys = new ArrayList<>();
            keys.add(checkpointKey(mediaId));
            keys.add(feedbackKey(mediaId));
            keys.add(goalIndexKey(mediaId));
            if (goalKeys != null) keys.addAll(goalKeys);
            redisTemplate.delete(keys);
        } catch (RuntimeException e) {
            log.warn("agent_checkpoint_cache_cleanup_failed mediaId={}", mediaId, e);
        }
    }

    private String checkpointKey(Long mediaId) {
        return "agent:checkpoint:" + mediaId + ":parse:" + parseVersion;
    }

    private String feedbackKey(Long mediaId) {
        return "agent:feedback:" + mediaId;
    }

    private String goalIndexKey(Long mediaId) {
        return checkpointKey(mediaId) + ":goals";
    }

    private String mediaCheckpoint(String field) {
        return "parse:" + parseVersion + ":media:" + field;
    }
}
