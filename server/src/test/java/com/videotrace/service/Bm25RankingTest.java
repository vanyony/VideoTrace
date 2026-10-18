package com.videotrace.service;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class Bm25RankingTest {
    @Test void normalizesChineseAndKeepsProductIdentifiers() {
        assertEquals(List.of("续航", "航改", "改善", "x100-pro"), Bm25Ranking.tokens("续航改善 Ｘ１００-Pro"));
        assertEquals(List.of("x100", "续航"), Bm25Ranking.tokens("X100续航"));
        assertEquals(0, Bm25Ranking.scores("X100", List.of("X1000"))[0]);
        assertTrue(Bm25Ranking.scores("续航", List.of("产品续航改善"))[0] > 0);
    }

    @Test void accountsForRarityFrequencyAndLengthRatherThanPresenceOnly() {
        var docs = List.of("common common", "common rare", "common filler", "other other");
        double[] rare = Bm25Ranking.scores("rare", docs);
        double[] common = Bm25Ranking.scores("common", docs);
        assertTrue(rare[1] > common[1]);
        double[] score = Bm25Ranking.scores("battery", List.of("battery", "battery " + "filler ".repeat(30), "battery battery"));
        assertTrue(score[0] > score[1]);
        assertTrue(score[2] > score[0]);
        // Old contains matching ties these documents; BM25 distinguishes relevance density.
        assertTrue(List.of("battery", "battery " + "filler ".repeat(30)).stream().allMatch(text -> text.contains("battery")));
        assertArrayEquals(new double[]{0, 0}, Bm25Ranking.scores("unknown", List.of("", "battery")));
    }

    @Test void rrfIsScaleIndependentAndDoesNotRankMissingEvidence() {
        double[] lexical = {0, 2, 1};
        assertArrayEquals(Bm25Ranking.fuse(new double[]{.9, .8, 0}, lexical),
                Bm25Ranking.fuse(new double[]{900, 800, 0}, lexical));
        double[] fused = Bm25Ranking.fuse(new double[]{.9, .8, 0}, lexical);
        assertTrue(fused[1] > fused[0]);
        assertArrayEquals(new double[]{0, 0}, Bm25Ranking.fuse(new double[]{0, Double.NaN}));
        assertEquals(Bm25Ranking.fuse(new double[]{1, 1})[0], Bm25Ranking.fuse(new double[]{1, 1})[1]);
    }
}
