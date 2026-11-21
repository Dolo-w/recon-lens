package io.github.dolow.reconlens.explain.semantics;

import io.github.dolow.reconlens.dsl.FieldRule;
import io.github.dolow.reconlens.dsl.RuleSpec;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 口径冲突检查（K3 的"知识变硬判定"）：规则两侧引用的列，语义字典里标注的
 * 口径（caliber）/单位（unit）不一致 → 逐条 WARN。
 *
 * <p><b>为什么是 WARN 不是 REJECT</b>：字典是人工登记的<b>理解</b>，不是数据本身 ——
 * 标注可能缺失、可能过时。它有资格把"净额 vs 含税"这种病在起草阶段喊出来，
 * 没资格直接毙掉一条结构合法的规则（那是闸门 1 的职责，且闸门只判结构与放宽）。
 * WARN 落进起草轨迹，审批人看得到 —— 判定权留在人手里，与信任梯度一脉相承。
 *
 * <p>可比对的前提：两侧<b>都</b>登记了该维度。只有一边知道口径不构成冲突证据
 * （"不知道"不能当"不一样"用），这是 fail 保守的又一处。
 */
@Component
public class CaliberConflictChecker {

    private final FieldSemanticDictionary dictionary;

    public CaliberConflictChecker(FieldSemanticDictionary dictionary) {
        this.dictionary = dictionary;
    }

    /** 规则里可机械判定的口径冲突列表（每条一句人话）。没有冲突/字典没覆盖 = 空列表。 */
    public List<String> conflicts(RuleSpec spec) {
        if (spec == null || !dictionary.enabled()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (FieldRule field : spec.compare()) {
            var left = dictionary.find(spec.left().table(), field.field());
            var right = dictionary.find(spec.right().table(), field.field());
            if (left.isEmpty() || right.isEmpty()) {
                continue;
            }
            String where = "字段 " + field.field() + "（" + spec.left().table() + " vs " + spec.right().table() + "）";

            if (bothPresent(left.get().caliber(), right.get().caliber())
                    && !same(left.get().caliber(), right.get().caliber())) {
                out.add(where + "口径冲突：左=" + left.get().caliber() + "，右=" + right.get().caliber()
                        + " —— 两侧金额口径不同，直接比对会产生系统性差异");
            }
            if (bothPresent(left.get().unit(), right.get().unit())
                    && !same(left.get().unit(), right.get().unit())) {
                out.add(where + "单位冲突：左=" + left.get().unit() + "，右=" + right.get().unit()
                        + " —— 先统一单位（或确认换算系数）再比对");
            }
        }
        return out;
    }

    private static boolean bothPresent(String a, String b) {
        return a != null && !a.isBlank() && b != null && !b.isBlank();
    }

    private static boolean same(String a, String b) {
        return Objects.equals(a.trim().toLowerCase(Locale.ROOT), b.trim().toLowerCase(Locale.ROOT));
    }
}
