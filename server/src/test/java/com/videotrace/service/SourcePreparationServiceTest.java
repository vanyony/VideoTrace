package com.videotrace.service;

import com.videotrace.dto.*;
import com.videotrace.entity.MediaFile;
import org.junit.jupiter.api.*;
import org.redisson.api.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SourcePreparationServiceTest {
    private final MediaService media=mock(MediaService.class);
    private final SourceCheckpointService checkpoints=mock(SourceCheckpointService.class);
    private final VideoContextService builder=mock(VideoContextService.class);
    private final SourceRetrievalService retrieval=mock(SourceRetrievalService.class);
    private final ProcessingDeliveryService deliveries=mock(ProcessingDeliveryService.class);
    private final ProcessingExecutionService executions=mock(ProcessingExecutionService.class);
    private final ProcessingTelemetry telemetry=mock(ProcessingTelemetry.class);
    private final RedissonClient locks=mock(RedissonClient.class);
    private final RLock lock=mock(RLock.class);
    private final StringRedisTemplate redis=mock(StringRedisTemplate.class);
    private final SourcePreparationService service=new SourcePreparationService(media,checkpoints,builder,retrieval,deliveries,executions,telemetry,locks,redis);
    private final VideoContext context=new VideoContext("source","",List.of(new VideoContext.VideoSegment(0,1000,"原视频事实",List.of(),List.of())));
    @BeforeEach void setup() {
        MediaFile file=new MediaFile();file.setId(7L);file.setUserId(3L);file.setFilePath("source");
        when(media.requireOwnedMedia(7L,3L)).thenReturn(file);
        when(media.exists(7L)).thenReturn(true);
        when(builder.parseVersion()).thenReturn("v1");
        when(locks.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);when(lock.isHeldByCurrentThread()).thenReturn(true);
    }
    private void state(String state) {
        when(executions.latest(7L,"准备创作素材:v1",ProcessingScope.GENERAL))
                .thenReturn(new ProcessingExecution("task",3L,7L,state,1,null));
    }
    @Test void alreadyParsedSourcesAreReusableWithoutAnalysisOrNewTasks() {
        when(checkpoints.loadContext(7L)).thenReturn(context);
        assertEquals(TaskStatus.State.COMPLETED,service.submit(3,7).state());
        verifyNoInteractions(deliveries,retrieval);
    }
    @Test void queueAndFailuresOverridePartialContextAndRetryUsesDurableTransport() {
        when(checkpoints.loadContext(7L)).thenReturn(context);
        state("RUNNING");
        assertEquals(TaskStatus.State.PROCESSING,service.submit(3,7).state());
        verifyNoInteractions(deliveries);
        state("FAILED");
        assertEquals(TaskStatus.State.FAILED,service.status(3,7).state());
        assertEquals(TaskStatus.State.QUEUED,service.submit(3,7).state());
        verify(deliveries).enqueue(eq(3L),argThat(m -> m.isPreparation() && "准备创作素材:v1".equals(m.getUserGoal())),isNull());
    }
    @Test void interruptedPreparationReusesSavedContextAndFinishesIndexing() {
        state("RUNNING");when(checkpoints.loadContext(7L)).thenReturn(context);
        service.prepare(7);
        verify(builder,never()).build(anyLong(),anyString(),anyString(),any());
        verify(retrieval).prepare(7L,context);
        verify(lock).unlock();
    }
    @Test void busyContentDefersAndCannotRunDuplicateParsing() {
        state("RUNNING");when(lock.tryLock()).thenReturn(false);
        assertThrows(com.videotrace.exception.ResourceBusyException.class,()->service.prepare(7));
        verifyNoInteractions(checkpoints,retrieval);
        verify(lock,never()).unlock();
    }
    @Test void sourceOwnershipIsCheckedBeforeSubmittingWork() {
        when(media.requireOwnedMedia(7L,8L)).thenThrow(new SecurityException());
        assertThrows(SecurityException.class,()->service.submit(8,7));
        verifyNoInteractions(deliveries,checkpoints);
    }
}
