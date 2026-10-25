package io.github.dolow.reconlens.recall;

import java.util.List;

/**
 * 文档检索的唯一入口。
 *
 * <p>做成接口而不是直接写 SQL，是为了让"换实现"不需要动调用方：
 * 现在是关键词（中文按二元组切），以后装得上 bge-m3 就换成向量，
 * 调用方仍然是 {@link #search(String, String, int)}。
 *
 * <p>约定：<b>先按元数据收窄，再算相关度</b>。反过来的实现（先全表算相似度再过滤）
 * 数据量一大就会变成"越用越慢"，而且过滤条件失效时没人会发现。
 */
public interface DocSearch {

    /**
     * @param query  用户原话，允许带空格分词；中文会被切成二元组
     * @param source 元数据过滤：文档来源（可为 null 表示不限）
     * @param limit  最多返回几条
     */
    List<DocHit> search(String query, String source, int limit);

    /** 给界面用的一句话说明"现在走的是哪条路"。 */
    String describe();
}
