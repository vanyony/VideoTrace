package com.videotrace.service;

import com.videotrace.dto.*;
import com.videotrace.mapper.VideoParseStageMapper;
import com.videotrace.utils.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VideoContextStageRecoveryTest {
    private final VideoParseStageMapper mapper=mock(VideoParseStageMapper.class);
    private final SegmentedTranscriptionService asr=mock(SegmentedTranscriptionService.class);
    private final MinioUtils minio=mock(MinioUtils.class);
    private final ThreadPoolTaskExecutor asrPool=pool();
    private final ThreadPoolTaskExecutor ocrPool=pool();
    private final Map<String,String> stored=new ConcurrentHashMap<>();
    private VideoContextService service;
    private static ThreadPoolTaskExecutor pool() {
        ThreadPoolTaskExecutor p=new ThreadPoolTaskExecutor();
        p.setCorePoolSize(1); p.setMaxPoolSize(1); p.setQueueCapacity(2); p.initialize(); return p;
    }
    @BeforeEach void setup() {
        when(minio.readableSource(anyString())).thenAnswer(c -> c.getArgument(0));
        when(minio.isManagedFile(anyString(),eq("evidence-frames"))).thenReturn(true);
        when(mapper.completed(anyLong(),anyString(),anyString())).thenAnswer(c ->
            stored.get(c.getArgument(0)+":"+c.getArgument(1)+":"+c.getArgument(2)));
        when(mapper.success(anyLong(),anyString(),anyString(),anyString())).thenAnswer(c -> {
            stored.putIfAbsent(c.getArgument(0)+":"+c.getArgument(1)+":"+c.getArgument(2),c.getArgument(3));
            return 1;
        });
        service=service("v1");
    }
    private VideoContextService service(String version) {
        return spy(new VideoContextService(asr,mock(OcrUtils.class),minio,asrPool,ocrPool,
            mock(AgentTelemetry.class),new VideoParseStageService(mapper,new ObjectMapper(),version),mock(ExternalCallLimiter.class)));
    }
    private List<TranscriptSegment> transcripts() {
        return List.of(new TranscriptSegment(0,60000,"产品信息"));
    }
    private List<VideoContextService.FramePart> frames() {
        return List.of(new VideoContextService.FramePart(1000,"参数","evidence-frames/frame.jpg"));
    }
    @AfterEach void cleanup() {
        asrPool.shutdown(); ocrPool.shutdown(); TaskExecutionContext.clear();
    }

    @Test void ocrFailureRetriesOnlyOcrAndPreservesAsr() throws Exception {
        when(asr.transcribe(anyString(),any(),any())).thenReturn(transcripts());
        AtomicInteger calls=new AtomicInteger();
        doAnswer(c -> {
            if (calls.getAndIncrement()==0) throw new IllegalStateException("OCR timeout");
            ((List<String>)c.getArgument(3)).add("evidence-frames/frame.jpg");
            return frames();
        }).when(service).extractKeyFrames(anyString(),any(),any(),anyList());
        assertThrows(IllegalStateException.class,() -> service.build(7L,"video.mp4","goal",null));
        assertTrue(stored.containsKey("7:v1:ASR"));
        assertFalse(stored.containsKey("7:v1:OCR"));
        VideoContext completed=service.build(7L,"video.mp4","goal",null);
        assertEquals("产品信息",completed.segments().getFirst().transcript());
        assertEquals(List.of("参数"),completed.segments().getFirst().ocrTexts());
        verify(asr,times(1)).transcribe(anyString(),any(),any());
        assertEquals(2,calls.get());
        verify(minio,never()).removeFile("evidence-frames/frame.jpg");
    }

    @Test void asrFailurePreservesSuccessfulOcrAndItsEvidenceFiles() throws Exception {
        when(asr.transcribe(anyString(),any(),any())).thenThrow(new IllegalStateException("ASR timeout")).thenReturn(transcripts());
        doAnswer(c -> {
            ((List<String>)c.getArgument(3)).add("evidence-frames/frame.jpg");
            return frames();
        }).when(service).extractKeyFrames(anyString(),any(),any(),anyList());
        assertThrows(IllegalStateException.class,() -> service.build(7L,"video.mp4","goal",null));
        assertTrue(stored.containsKey("7:v1:OCR"));
        service.build(7L,"video.mp4","goal",null);
        verify(service,times(1)).extractKeyFrames(anyString(),any(),any(),anyList());
        verify(asr,times(2)).transcribe(anyString(),any(),any());
        verify(minio,never()).removeFile("evidence-frames/frame.jpg");
    }

    @Test void freshServiceInstanceReusesPersistedStagesAndNewVersionRebuilds() throws Exception {
        when(asr.transcribe(anyString(),any(),any())).thenReturn(transcripts());
        doReturn(frames()).when(service).extractKeyFrames(anyString(),any(),any(),anyList());
        service.build(7L,"video.mp4","goal",null);
        VideoContextService restarted=service("v1");
        restarted.build(7L,"video.mp4","another goal",null);
        verify(restarted,never()).extractKeyFrames(anyString(),any(),any(),anyList());
        verify(asr,times(1)).transcribe(anyString(),any(),any());

        VideoContextService upgraded=service("v2");
        doReturn(frames()).when(upgraded).extractKeyFrames(anyString(),any(),any(),anyList());
        upgraded.build(7L,"video.mp4","goal",null);
        verify(asr,times(2)).transcribe(anyString(),any(),any());
        assertTrue(stored.containsKey("7:v2:OCR"));
    }

    @Test void expiredExecutionCannotPersistBranchResults() throws Exception {
        when(asr.transcribe(anyString(),any(),any())).thenReturn(transcripts());
        doReturn(frames()).when(service).extractKeyFrames(anyString(),any(),any(),anyList());
        TaskExecutionContext.bind(work -> { throw new TaskExecutionContext.LeaseLostException(); });
        assertThrows(IllegalStateException.class,() -> service.build(7L,"video.mp4","goal",null));
        assertTrue(stored.isEmpty());
        verify(mapper,never()).success(anyLong(),anyString(),anyString(),anyString());
    }

    @Test void mediaDeletionCleansEvidenceFromEveryParseVersion() throws Exception {
        when(mapper.frames(7L)).thenReturn(List.of(new ObjectMapper().writeValueAsString(frames())));
        service.deleteParseArtifacts(7L);
        verify(minio).removeFile("evidence-frames/frame.jpg");
        verify(mapper).deleteMedia(7L);
    }
}

