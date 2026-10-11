package io.github.dolow.reconlens.dsl;

import io.github.dolow.reconlens.domain.DiffKind;
import io.github.dolow.reconlens.domain.Severity;

import java.util.List;
import java.util.Optional;

/**
 * 一条对账规则的全部内容。规则来自 YAML，不含任何 Java 代码 —— 换一套对账逻辑只改配置。
 */
public record RuleSpec(String code,
                       String name,
                       String description,
                       SideSpec left,
                       SideSpec right,
                       ShardSpec shard,
                       List<FieldRule> compare,
                       MissingSpec missing,
                       DuplicateSpec duplicate,
                       int maxAttempts) {

    public Optional<FieldRule> field(String field) {
        return compare.stream().filter(f -> f.field().equalsIgnoreCase(field)).findFirst();
    }

    /** 把规则里自由的 diffType 字符串归类到引擎内部的语义种类，归因模板只依赖 DiffKind。 */
    public DiffKind classify(String diffType) {
        if (diffType == null) {
            return DiffKind.VALUE_MISMATCH;
        }
        if (diffType.equals(duplicate.diffType())) {
            return DiffKind.DUPLICATE_KEY;
        }
        if (diffType.equals(missing.leftMissingDiffType())) {
            return DiffKind.MISSING_LEFT;
        }
        if (diffType.equals(missing.rightMissingDiffType())) {
            return DiffKind.MISSING_RIGHT;
        }
        return DiffKind.VALUE_MISMATCH;
    }

    public Severity severityOf(String diffType) {
        return field(diffType).map(FieldRule::severity).orElseGet(() ->
                compare.stream()
                        .filter(f -> f.diffType().equals(diffType))
                        .map(FieldRule::severity)
                        .findFirst()
                        .orElse(Severity.MEDIUM));
    }
}
