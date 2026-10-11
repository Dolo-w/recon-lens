package io.github.dolow.reconlens.engine;

/** 单个分片的对账统计。 */
public record MatchStats(long scannedLeft, long scannedRight, long matchedRows, long diffRows) {

    public static MatchStats empty() {
        return new MatchStats(0, 0, 0, 0);
    }
}
