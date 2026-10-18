package com.videotrace.service;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/** Per-video BM25 corpus; no external search service. Chinese uses overlapping character bigrams. */
final class Bm25Ranking {
    private static final Pattern TOKENS = Pattern.compile("\\p{IsHan}+|[\\p{IsLatin}\\p{N}]+(?:[-_.][\\p{IsLatin}\\p{N}]+)*");

    static List<String> tokens(String text) {
        var result = new ArrayList<String>();
        var matcher = TOKENS.matcher(Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String word = matcher.group();
            int[] cps = word.codePoints().toArray();
            if (Character.UnicodeScript.of(cps[0]) == Character.UnicodeScript.HAN && cps.length > 1) {
                for (int i = 0; i < cps.length - 1; i++) result.add(new String(cps, i, 2));
            } else result.add(word);
        }
        return result;
    }

    static double[] scores(String query, List<String> documents) {
        var frequencies = new ArrayList<Map<String, Integer>>();
        var df = new HashMap<String, Integer>();
        int[] lengths = new int[documents.size()];
        for (int i = 0; i < documents.size(); i++) {
            var terms = tokens(documents.get(i));
            lengths[i] = terms.size();
            var tf = new HashMap<String, Integer>();
            terms.forEach(term -> tf.merge(term, 1, Integer::sum));
            tf.keySet().forEach(term -> df.merge(term, 1, Integer::sum));
            frequencies.add(tf);
        }
        double average = Arrays.stream(lengths).average().orElse(0);
        double[] scores = new double[documents.size()];
        if (average == 0) return scores;
        var queryTerms = new HashSet<>(tokens(query));
        for (int i = 0; i < scores.length; i++) {
            for (String term : queryTerms) {
                int tf = frequencies.get(i).getOrDefault(term, 0);
                if (tf == 0) continue;
                double idf = Math.log1p((documents.size() - df.get(term) + 0.5) / (df.get(term) + 0.5));
                scores[i] += idf * tf * 2.2 / (tf + 1.2 * (0.25 + 0.75 * lengths[i] / average));
            }
        }
        return scores;
    }

    /** RRF k=60; absent/nonpositive hits contribute nothing; equal scores share a rank. */
    static double[] fuse(double[]... channels) {
        double[] result = new double[channels[0].length];
        for (double[] channel : channels) {
            var order = IntStream.range(0, channel.length).boxed()
                    .filter(i -> Double.isFinite(channel[i]) && channel[i] > 0)
                    .sorted(Comparator.<Integer>comparingDouble(i -> channel[i]).reversed()).toList();
            int rank = 0;
            double previous = Double.NaN;
            for (int j = 0; j < order.size(); j++) {
                int i = order.get(j);
                if (Double.compare(previous, channel[i]) != 0) rank = j + 1;
                result[i] += 1.0 / (60 + rank);
                previous = channel[i];
            }
        }
        return result;
    }
}
