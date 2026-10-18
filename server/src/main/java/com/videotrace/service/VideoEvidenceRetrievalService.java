package com.videotrace.service;

import com.videotrace.dto.VideoChunk;
import com.videotrace.dto.VideoContext;
import com.videotrace.dto.VideoEvidenceHit;
import com.videotrace.dto.VideoRetrievalIntent;
import com.videotrace.utils.DeepSeekUtils;
import com.videotrace.utils.EmbeddingUtils;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 查询感知的视频证据检索：语义召回负责“说了什么”，OCR 通道负责“画面写了什么”。 */
@Service
public class VideoEvidenceRetrievalService {

    private static final int TOP_K = 3;
    private static final int MAX_USER_HITS = 8;
    private static final int MAX_SNIPPET_LENGTH = 180;

    private final DeepSeekUtils deepSeekUtils;
    private final EmbeddingUtils embeddingUtils;
    private final QdrantVectorStore vectorStore;
    private final AgentTelemetry telemetry;

    public VideoEvidenceRetrievalService(DeepSeekUtils deepSeekUtils,
                                         EmbeddingUtils embeddingUtils,
                                         QdrantVectorStore vectorStore,
                                         AgentTelemetry telemetry) {
        this.deepSeekUtils = deepSeekUtils;
        this.embeddingUtils = embeddingUtils;
        this.vectorStore = vectorStore;
        this.telemetry = telemetry;
    }

    public List<VideoContext.VideoSegment> retrieve(Long mediaId,
                                                     String goal,
                                                     List<VideoChunk> chunks) {
        return rank(mediaId, goal, chunks).stream()
                .map(ScoredSegment::segment)
                .toList();
    }

    public List<VideoEvidenceHit> search(Long mediaId,
                                         String query,
                                         List<VideoChunk> chunks) {
        return rank(mediaId, query, chunks).stream()
                .limit(MAX_USER_HITS)
                .map(this::toHit)
                .toList();
    }

    private List<ScoredSegment> rank(Long mediaId,
                                     String goal,
                                     List<VideoChunk> chunks) {
        VideoRetrievalIntent intent = retrievalIntent(goal);
        List<Double> queryEmbedding = embed(intent.semanticQuery());
        Map<String, Double> vectorScores = vectorScores(mediaId, queryEmbedding);

        String lexicalQuery = String.join(" ", intent.semanticQuery(),
                String.join(" ", intent.keywords()), String.join(" ", intent.visualKeywords()));
        double[] semantic = chunks.stream().mapToDouble(chunk -> vectorScores.getOrDefault(
                chunkKey(chunk), cosine(queryEmbedding, chunk.embedding()))).toArray();
        double[] lexical = Bm25Ranking.scores(lexicalQuery, chunks.stream()
                .map(chunk -> searchableText(chunk) + " " + visualText(chunk)).toList());
        double[] fused = Bm25Ranking.fuse(semantic, lexical);
        List<ScoredChunk> rankedChunks = java.util.stream.IntStream.range(0, chunks.size())
                .filter(i -> fused[i] > 0)
                .mapToObj(i -> new ScoredChunk(chunks.get(i), fused[i]))
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed()
                        .thenComparingLong(c -> c.chunk().startTime()))
                .limit(TOP_K).toList();
        if (!rankedChunks.isEmpty()) {
            telemetry.valueCurrent("retrievalTopScore",
                    rankedChunks.get(0).score());
            telemetry.incrementCurrent("retrievalChunks", rankedChunks.size());
        }
        var candidates = new LinkedHashMap<String, ScoredSegment>();
        for (ScoredChunk chunk : rankedChunks) {
            for (var segment : chunk.chunk().rawSegments()) {
                String key = segment.startMs() + ":" + segment.endMs();
                candidates.putIfAbsent(key, new ScoredSegment(segment, chunk.score(), 0, 0));
            }
        }
        var segments = List.copyOf(candidates.values());
        double[] transcript = Bm25Ranking.scores(lexicalQuery, segments.stream()
                .map(s -> s.segment().transcript()).toList());
        double[] visual = Bm25Ranking.scores(lexicalQuery, segments.stream()
                .map(s -> String.join(" ", normalizedOcrTexts(s.segment()))).toList());
        double[] transcriptRanks = Bm25Ranking.fuse(transcript);
        double[] visualRanks = Bm25Ranking.fuse(visual);
        double[] segmentScores = Bm25Ranking.fuse(segments.stream().mapToDouble(ScoredSegment::score).toArray(), transcript, visual);
        return java.util.stream.IntStream.range(0, segments.size())
                .mapToObj(i -> new ScoredSegment(segments.get(i).segment(), segmentScores[i], transcriptRanks[i], visualRanks[i]))
                .sorted(Comparator.comparingDouble(ScoredSegment::score).reversed()
                        .thenComparingLong(result -> result.segment().startMs()))
                .toList();
    }

    public void index(Long mediaId, List<VideoChunk> chunks) {
        try {
            vectorStore.upsert(mediaId, chunks);
            telemetry.incrementCurrent("vectorStoreWrites", chunks.size());
        } catch (RuntimeException e) {
            // 向量库挂了仍可走内存向量和关键词，别让检索基础设施拖垮分析主链路。
            telemetry.incrementCurrent("vectorStoreFallbacks", 1);
        }
    }

    private Map<String, Double> vectorScores(Long mediaId, List<Double> queryEmbedding) {
        Map<String, Double> scores = new LinkedHashMap<>();
        if (mediaId == null || queryEmbedding.isEmpty()) return scores;
        try {
            vectorStore.search(mediaId, queryEmbedding, TOP_K * 2).forEach(hit ->
                    scores.put(hit.startMs() + ":" + hit.endMs(), hit.score()));
        } catch (RuntimeException e) {
            telemetry.incrementCurrent("vectorStoreFallbacks", 1);
        }
        return scores;
    }

    private VideoEvidenceHit toHit(ScoredSegment result) {
        VideoContext.VideoSegment segment = result.segment();
        List<String> ocrTexts = normalizedOcrTexts(segment);
        boolean hasTranscript = !segment.transcript().isBlank();
        boolean hasOcr = !ocrTexts.isEmpty();
        String source = hasTranscript && hasOcr
                ? "ASR+OCR"
                : hasOcr ? "OCR" : hasTranscript ? "ASR" : "时间片段";
        String ocrText = String.join(" ", ocrTexts);
        String preferred = result.visualScore() > result.transcriptScore() ? ocrText : segment.transcript();
        if (preferred.isBlank()) preferred = hasOcr ? ocrText : segment.transcript();
        if (preferred.isBlank()) preferred = "该时间段暂无可展示文本";
        return new VideoEvidenceHit(
                segment.startMs(),
                segment.endMs(),
                source,
                abbreviate(preferred),
                segment.transcript(),
                ocrTexts);
    }

    private String searchableText(VideoChunk chunk) {
        return String.join(" ",
                chunk.segmentSummary(),
                String.join(" ", chunk.keywords()),
                chunk.rawSegments().stream()
                        .map(VideoContext.VideoSegment::transcript)
                        .collect(java.util.stream.Collectors.joining(" ")));
    }

    private String visualText(VideoChunk chunk) {
        return chunk.rawSegments().stream()
                .flatMap(segment -> normalizedOcrTexts(segment).stream())
                .collect(java.util.stream.Collectors.joining(" "));
    }

    private List<String> normalizedOcrTexts(VideoContext.VideoSegment segment) {
        return segment.ocrTexts().stream()
                .filter(java.util.Objects::nonNull)
                .map(String::trim)
                .filter(text -> !text.isBlank())
                .distinct()
                .toList();
    }

    private double cosine(List<Double> left, List<Double> right) {
        if (left.size() != right.size() || left.isEmpty()) return 0;
        double dot = 0;
        double leftLength = 0;
        double rightLength = 0;
        for (int i = 0; i < left.size(); i++) {
            dot += left.get(i) * right.get(i);
            leftLength += left.get(i) * left.get(i);
            rightLength += right.get(i) * right.get(i);
        }
        if (leftLength == 0 || rightLength == 0) return 0;
        return dot / (Math.sqrt(leftLength) * Math.sqrt(rightLength));
    }

    private VideoRetrievalIntent retrievalIntent(String goal) {
        try {
            VideoRetrievalIntent intent = deepSeekUtils.planRetrieval(goal);
            if (!intent.semanticQuery().isBlank()) return intent;
        } catch (RuntimeException e) {
            telemetry.incrementCurrent("retrievalIntentFallbacks", 1);
        }
        return new VideoRetrievalIntent(goal, fallbackTerms(goal), fallbackTerms(goal));
    }

    private List<Double> embed(String text) {
        try {
            return embeddingUtils.embed(text);
        } catch (RuntimeException e) {
            telemetry.incrementCurrent("embeddingFallbacks", 1);
            return List.of();
        }
    }

    private String chunkKey(VideoChunk chunk) {
        return chunk.startTime() + ":" + chunk.endTime();
    }

    private List<String> fallbackTerms(String query) {
        if (query == null || query.isBlank()) return List.of();
        List<String> terms = java.util.Arrays.stream(query.trim().split("[\\s，。！？、,.;:：；!?]+"))
                .map(String::trim)
                .filter(term -> term.length() >= 2)
                .distinct()
                .limit(8)
                .toList();
        return terms.isEmpty() ? List.of(query.trim()) : terms;
    }

    private String abbreviate(String value) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= MAX_SNIPPET_LENGTH
                ? normalized
                : normalized.substring(0, MAX_SNIPPET_LENGTH) + "...";
    }

    private record ScoredChunk(VideoChunk chunk, double score) {
    }

    private record ScoredSegment(
            VideoContext.VideoSegment segment,
            double score,
            double transcriptScore,
            double visualScore
    ) {
    }
}
