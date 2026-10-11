package io.github.dolow.reconlens.explain;

import io.github.dolow.reconlens.domain.DiffRecord;
import io.github.dolow.reconlens.dsl.RuleSpec;

/** 差异归因的出口：规则模板与大模型两种实现共用这个接口。 */
public interface DiffExplainer {

    String name();

    String explain(RuleSpec rule, DiffRecord diff);
}
