package com.videotrace.service;

import com.videotrace.dto.*;
import com.videotrace.entity.MediaFile;
import com.videotrace.mapper.MediaFileMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DurableTranscriptionTest {
    private final MediaFileMapper mapper = mock(MediaFileMapper.class);
    private final VideoTranscriptionService transcriber = mock(VideoTranscriptionService.class);
    private final MediaService media = mock(MediaService.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final TaskEventService events = mock(TaskEventService.class);
    private final ProcessingDeliveryService deliveries = mock(ProcessingDeliveryService.class);
    private final ProcessingExecutionService executions = mock(ProcessingExecutionService.class);
    private final TranscriptionTaskService service = new TranscriptionTaskService(
            mapper, transcriber, media, redis, events, deliveries, executions);

    private MediaFile file() {
        MediaFile file = new MediaFile(); file.setId(7L); file.setUserId(3L); return file;
    }

    @Test void acceptanceIsDurableAndNotificationFailureDoesNotUndoIt() {
        doThrow(new IllegalStateException("Redis down")).when(events)
                .publishTranscription(eq(7L), any(), any());
        assertTrue(service.submit(file()));
        verify(deliveries).enqueue(eq(3L), argThat(m -> m.isTranscription()
                && m.getMediaId().equals(7L)), isNull());
        verifyNoInteractions(transcriber, redis);
    }

    @Test void databaseUniquenessRejectsConcurrentSubmission() {
        when(deliveries.enqueue(anyLong(), any(), isNull())).thenThrow(new DuplicateKeyException("active"));
        assertFalse(service.submit(file()));
        verifyNoInteractions(transcriber);
    }

    @Test void durableStateOverridesStaleRedisAndOldTranscript() {
        MediaFile file = file(); file.setTranscriptText("old");
        when(executions.latestTranscription(7L)).thenReturn(new ProcessingExecution("id",3L,7L,"QUEUED",1,null));
        assertEquals(TaskStatus.State.QUEUED, service.status(file).state());
        when(executions.latestTranscription(7L)).thenReturn(new ProcessingExecution("id",3L,7L,"FAILED",3,"LeaseExpired"));
        assertEquals(TaskStatus.State.FAILED, service.status(file).state());
        verifyNoInteractions(redis);
    }

    @Test void oldOrphanedRedisFlagCannotKeepTaskBusy() {
        ValueOperations<String,String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("transcription:state:7")).thenReturn("PROCESSING");
        when(redis.hasKey("transcription:active:7")).thenReturn(true);
        assertEquals(TaskStatus.State.FAILED, service.status(file()).state());
    }

    @Test void workerFailurePropagatesToDurableRetryInsteadOfBeingAcknowledged() {
        when(mapper.selectById(7L)).thenThrow(new IllegalStateException("DB down"));
        assertThrows(IllegalStateException.class, () -> service.transcribe(7L));
        verify(events, never()).publishTranscription(anyLong(), any(), any());
    }

    @Test void leaseLossDuringAsrPreventsStaleTranscriptWrite() {
        MediaFile file=file(); file.setFilePath("source");
        when(mapper.selectById(7L)).thenReturn(file);
        when(media.readableSource("source")).thenReturn("readable");
        java.util.concurrent.atomic.AtomicBoolean lost = new java.util.concurrent.atomic.AtomicBoolean();
        when(transcriber.transcribe("readable")).thenAnswer(c -> { lost.set(true); return "stale result"; });
        TaskExecutionContext.bind(operation -> {
            if (lost.get()) throw new TaskExecutionContext.LeaseLostException();
            operation.run();
        });
        try {
            assertThrows(TaskExecutionContext.LeaseLostException.class, () -> service.transcribe(7L));
            verify(mapper, never()).updateById(any(MediaFile.class));
        } finally { TaskExecutionContext.clear(); }
    }
}
