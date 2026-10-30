package com.videotrace.repository;

import com.videotrace.dto.VideoContext;
import com.videotrace.dto.TaskStage;
import com.videotrace.mapper.SourceCheckpointMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SourceCheckpointRepositoryTest {
    @Test
    void reloadingContextCannotRewindTheChunkStage() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);
        Map<Object, Object> cache = new HashMap<>();
        when(hashes.get(eq("key"), any())).thenAnswer(call -> cache.get(call.getArgument(1)));
        doAnswer(call -> { cache.put(call.getArgument(1), call.getArgument(2)); return null; })
                .when(hashes).put(eq("key"), any(), any());
        SourceCheckpointMapper mapper = mock(SourceCheckpointMapper.class);
        when(mapper.findPayload(7L, "media:context")).thenReturn("{\"source\":\"source\",\"userGoal\":\"\",\"segments\":[]}");
        when(mapper.findStage(7L, "media:context")).thenReturn("CONTEXT_COMPLETED");
        when(mapper.findStage(7L, "media:stage")).thenReturn("CHUNKS_COMPLETED");
        SourceCheckpointRepository repository = new SourceCheckpointRepository(redis, new ObjectMapper(), mapper);

        assertNotNull(repository.read(7L, "media:context", "key", "context", VideoContext.class));
        assertEquals(TaskStage.CHUNKS_COMPLETED, repository.readStage(7L, "media:stage", "key"));
        assertEquals("CHUNKS_COMPLETED", cache.get("stage"));
    }
}
