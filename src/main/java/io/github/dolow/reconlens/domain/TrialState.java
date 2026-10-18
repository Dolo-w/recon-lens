package io.github.dolow.reconlens.domain;

import java.util.List;

/**
 * 试算状态。<b>把"有判定结果"和"没有判定结果"拆成类型</b>，因为这正是 v0.6 那格
 * {@code SKIPPED × NONE} 一直填错的根因：一张表同时管两类状态，必然有一类落不到格子上。
 *
 * <ul>
 *   <li>{@link NotRun}：还没跑（刚建草稿 / 排队中）；</li>
 *   <li>{@link Skipped}：决定不跑，必须带理由（资格由服务端字段级 diff 判定，不由人自述）；</li>
 *   <li>{@link Trialed}：跑出了判定结果，必须同时有 {@code coverage} 与理由。</li>
 * </ul>
 *
 * <p>{@link Trialed} 的构造器就是"16 格非法组合"的编译期防线：这里拦不住，就永远拦不住。
 * 注意 {@code INCONCLUSIVE + NONE} <b>允许构造</b>（闸门坏了要能落库等修），只是 {@link #approval()}
 * 返回 {@link ApprovalMode#BLOCK_HARD} —— "能构造 ≠ 能批"。
 */
public sealed interface TrialState permits TrialState.NotRun, TrialState.Skipped, TrialState.Trialed {

    /** 审批接口唯一入口：只读这里，不要在 Controller / Service 里再写一份 if。 */
    ApprovalMode approval();

    /** 未跑：verdict / coverage 落库都是 NULL。 */
    record NotRun() implements TrialState {

        @Override
        public ApprovalMode approval() {
            return ApprovalMode.NOT_APPROVABLE;
        }
    }

    /** 跳过试算：verdict=SKIPPED、coverage=NULL。理由非空是构造条件，不是注释约定。 */
    record Skipped(String skipReason) implements TrialState {

        public Skipped {
            if (skipReason == null || skipReason.isBlank()) {
                throw new IllegalArgumentException("跳过试算必须写明理由（skip_reason）");
            }
        }

        @Override
        public ApprovalMode approval() {
            return ApprovalMode.NO_SIGNOFF_REQUIRED;
        }
    }

    /** 跑过试算：{@code coverage} 与理由都是必需的。 */
    record Trialed(TrialVerdict verdict, Coverage coverage, List<CoverageReason> reasons) implements TrialState {

        public Trialed {
            if (verdict == null) {
                throw new IllegalStateException("判定结果不能为空；未跑请用 NotRun");
            }
            if (coverage == null) {
                throw new IllegalStateException("有判定结果就必须有 coverage；NULL 只属于 NotRun / Skipped");
            }
            if (reasons == null) {
                reasons = List.of();
            }
            // 只有"验全了"才允许没有降级理由。FULL 之外必须说清为什么没验全 ——
            // 否则"覆盖不完整"这件事就只剩一个枚举值，排查时无从下手。
            // （v0.8 的伪代码要求 reasons 恒非空；实现时发现 FULL 下只能编一个理由出来，
            //   等于让"理由"变成形式主义，故收窄到 coverage != FULL。）
            if (coverage != Coverage.FULL && reasons.isEmpty()) {
                throw new IllegalStateException("coverage=" + coverage + " 时必须写明为什么没验全（CoverageReason）");
            }
            switch (verdict) {
                case PASS, FAIL -> {
                    if (coverage != Coverage.FULL && coverage != Coverage.SAMPLE_PARTIAL) {
                        throw new IllegalStateException(verdict + " + " + coverage
                                + " 不合法：没跑数据不可能得出 " + verdict);
                    }
                }
                case INCONCLUSIVE -> {
                    if (coverage == Coverage.FULL) {
                        throw new IllegalStateException("INCONCLUSIVE + FULL 不合法：全验了还不确定 = 判定逻辑 bug");
                    }
                }
                case SKIPPED -> throw new IllegalStateException("SKIPPED 没有判定结果，请用 TrialState.Skipped");
            }
            reasons = List.copyOf(reasons);
        }

        @Override
        public ApprovalMode approval() {
            return switch (verdict) {          // 不写 default：新增 verdict 值即编译失败
                case FAIL -> ApprovalMode.BLOCK_HARD;   // 结论已明确不合格，人不能"签字接受一个错规则"
                case PASS -> coverage == Coverage.FULL ? ApprovalMode.NO_SIGNOFF_REQUIRED : ApprovalMode.NEED_SIGN;
                // 走到 NEED_SIGN 时 coverage 必然只剩 SAMPLE_PARTIAL（FIELDS_ONLY / NONE 已被构造器拒掉）
                case INCONCLUSIVE -> coverage == Coverage.NONE ? ApprovalMode.BLOCK_HARD : ApprovalMode.BLOCK_SIGNABLE;
                case SKIPPED -> throw new IllegalStateException("SKIPPED 请用 TrialState.Skipped");
            };
        }
    }
}
