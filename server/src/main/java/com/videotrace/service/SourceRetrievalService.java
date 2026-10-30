package com.videotrace.service;

import com.videotrace.dto.VideoChunk;
import com.videotrace.dto.VideoContext;
import com.videotrace.dto.VideoEvidenceHit;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SourceRetrievalService {


    private final ProcessingTelemetry telemetry;
    private final SourceCheckpointService checkpointService;
    private final VideoChunkingService chunkingService;
    private final VideoEvidenceRetrievalService retrievalService;

    public SourceRetrievalService(ProcessingTelemetry telemetry,
                                   SourceCheckpointService checkpointService,
                                   VideoChunkingService chunkingService,
                                   VideoEvidenceRetrievalService retrievalService) {
        this.telemetry = telemetry;
        this.checkpointService = checkpointService;
        this.chunkingService = chunkingService;
        this.retrievalService = retrievalService;
    }

    public List<VideoEvidenceHit> searchEvidence(Long mediaId, VideoContext context) {
        if (context.segments().isEmpty()) return List.of();
        List<VideoChunk> chunks = resolveChunks(mediaId, context.segments());
        return retrievalService.search(mediaId, context.userGoal(), chunks);
    }

    public void prepare(Long mediaId, VideoContext context) {
        List<VideoChunk> chunks = resolveChunks(mediaId, context.segments());
        // Reindex even when chunk generation succeeded before an interrupted indexing step.
        retrievalService.index(mediaId, chunks);
    }

    private List<VideoChunk> resolveChunks(Long mediaId,
                                           List<VideoContext.VideoSegment> segments) {
        List<VideoChunk> chunks = mediaId == null ? null : checkpointService.loadChunks(mediaId);
        if (chunks != null && !chunks.isEmpty()) {
            telemetry.incrementCurrent("chunkCheckpointHits", 1);
            return chunks;
        }

        chunks = chunkingService.build(segments);
        if (mediaId != null) {
            checkpointService.saveChunks(mediaId, chunks);
        }
        return chunks;
    }

}
