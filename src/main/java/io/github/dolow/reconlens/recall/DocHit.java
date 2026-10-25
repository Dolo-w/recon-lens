package io.github.dolow.reconlens.recall;

import java.util.List;

/** 一条命中的文档切片。{@code matched} 是命中的词，回显出来让人判断"为什么这条被选中"。 */
public record DocHit(DocChunk chunk, int score, List<String> matched) {
}
