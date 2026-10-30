package com.videotrace.service;

import com.videotrace.dto.*;
import com.videotrace.utils.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HybridEvidenceRetrievalTest {
    private VideoChunk chunk(long start, String text, List<Double> vector) {
        return new VideoChunk(start, start + 1000, text, List.of(),
                List.of(new VideoContext.VideoSegment(start, start + 1000, text, List.of(), List.of())), vector);
    }

    @Test void preciseTermsAndSemanticParaphrasesRetrieveTimestampedEvidence() {
        var llm = mock(TextModelClient.class);
        var embedding = mock(EmbeddingUtils.class);
        var store = mock(QdrantVectorStore.class);
        var service = new VideoEvidenceRetrievalService(llm, embedding, store, mock(ProcessingTelemetry.class));
        var chunks = List.of(chunk(0, "X1000 display", List.of(0.0, 1.0)),
                chunk(1000, "X100 camera", List.of(0.0, 1.0)),
                chunk(2000, "续航改善", List.of(1.0, 0.0)));
        when(llm.planRetrieval("X100")).thenReturn(new VideoRetrievalIntent("X100", List.of("X100"), List.of()));
        when(embedding.embed("X100")).thenThrow(new RuntimeException("offline"));
        assertEquals(1000, service.search(null, "X100", chunks).getFirst().startMs());
        when(llm.planRetrieval("更久不用充电")).thenReturn(new VideoRetrievalIntent("更久不用充电", List.of(), List.of()));
        when(embedding.embed("更久不用充电")).thenReturn(List.of(1.0, 0.0));
        assertEquals(2000, service.search(null, "更久不用充电", chunks).getFirst().startMs());
        when(llm.planRetrieval("没有匹配")).thenThrow(new RuntimeException("offline"));
        assertTrue(service.search(null, "没有匹配", chunks).isEmpty());
    }
}
