package io.github.dolow.reconlens.domain;

/**
 * 一条差异的<b>原始行凭证</b>：差异落库时刻，来源表那一整行的快照。
 *
 * <p>为什么是快照不是外键引用：recon_diff 只有 left_value/right_value 两个比对字段值，
 * 复盘"这条差异当时两侧数据长什么样"需要回源表查 —— 但源表是会变的（补数、重跑、
 * 归档），等财务来对质时可能已经查不回当时的样子。凭证在差异写出的同一事务里落库，
 * 之后源表怎么变都不影响复盘口径。
 *
 * <p>与 recon_diff 的关联是<b>自然键</b> (job_id, shard_id, biz_key)，不存 diff_id：
 * 同一行左侧数据产出 3 个字段差异时，凭证只存一份（唯一索引去重），不跟着差异翻倍。
 *
 * @param side    LEFT / RIGHT —— 凭证属于哪一侧
 * @param bizKey  该行的业务键值
 * @param source  来源描述（表名或"自定义SQL"），人看得懂就行
 * @param rowJson 整行的 JSON 快照，超长截断并带标记（见 {@code Row.toJson}）
 */
public record DiffEvidence(long jobId, int shardId, String side, String bizKey,
                           String source, String rowJson) {

    public static final String LEFT = "LEFT";
    public static final String RIGHT = "RIGHT";
}
