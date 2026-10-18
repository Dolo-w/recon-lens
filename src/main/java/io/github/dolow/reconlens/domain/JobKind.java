package io.github.dolow.reconlens.domain;

/**
 * 任务种类。<b>试算与 canary 也写 {@code recon_job}</b>（否则拿不到分片记录与差异），
 * 所以任务表必须能区分"这是生产对账"和"这是闸门在自检"。
 *
 * <p>不区分会直接制造误报：巡检看的是"最近 N 次任务的差异率环比、新出现的 {@code diff_type}"，
 * 而试算跑的是裁剪过的样本，差异率天然不同 —— 它们一旦混进基线，巡检就会为一份被裁剪的数据报警。
 *
 * <p><b>过滤落点</b>（不是"给所有查询都加 kind"）：per-job 查询（{@code findJob} / {@code listShards} /
 * {@code listDiffs}）天然按 jobId 隔离，<b>不加</b>；要加的是"跨任务看趋势"的地方 ——
 * {@code listJobs}、Web 任务列表、以及 {@code countBy(column, null)} 这种全表分支。
 */
public enum JobKind {

    /** 生产对账。 */
    PROD,

    /** 影子试算 / canary 自检。默认对业务视图不可见。 */
    TRIAL
}
