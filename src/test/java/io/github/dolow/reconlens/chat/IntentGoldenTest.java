package io.github.dolow.reconlens.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * 意图 golden 回归集 —— 业界说的「golden benchmark 卡住每次改动」。
 *
 * <p>语料来自两个渠道：最初手工核证的说法 + fallback 挖掘确认后沉淀的新说法。
 * 每条跑真实词表路由断言期望意图；<b>只钉 intent 不钉参数</b>（参数归
 * SlotProtocol 与参数抽取测试）。测试失败先修路由再合码——这是这条流水线的全部意义。
 *
 * <p>防静默通过：语料文件读不到或 cases 为空都算失败。空集的 golden
 * 等于没有 golden，宁可红也不许假绿。
 */
class IntentGoldenTest {

    private static final String GOLDEN = "/golden/intent-golden.yml";

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> loadCases() {
        InputStream in = getClass().getResourceAsStream(GOLDEN);
        assertThat(in).as("golden 语料文件必须存在：%s", GOLDEN).isNotNull();
        Map<String, Object> root = new Yaml().load(in);
        Object cases = root == null ? null : root.get("cases");
        assertThat(cases).as("golden 语料的 cases 不能为空——空集等于没有 golden").isNotNull();
        return (List<Map<String, Object>>) cases;
    }

    @Test
    @DisplayName("golden 全量回归：每条说法命中期望意图")
    void goldenCasesPass() {
        List<Map<String, Object>> cases = loadCases();
        assertThat(cases.size()).as("golden 至少要护住 20 条说法").isGreaterThanOrEqualTo(20);

        ChatIntentRouter router = new ChatIntentRouter();
        List<String> failures = new ArrayList<>();
        for (Map<String, Object> c : cases) {
            String text = String.valueOf(c.get("text"));
            String expected = String.valueOf(c.get("intent"));
            assertThat(expected)
                    .as("语料「%s」的 intent 不能是 TODO——先人工确认再进 golden", text)
                    .isNotEqualTo("TODO").isNotEqualTo("null");
            ChatIntent actual = router.route(text).intent();
            if (!actual.name().equals(expected)) {
                failures.add("「" + text + "」期望 " + expected + " 实得 " + actual);
            }
        }
        assertThat(failures).as("golden 回归失败（先修路由，不许改期望）").isEmpty();
    }

    @Test
    @DisplayName("语料卫生：text 与 intent 字段齐全、无重复说法")
    void corpusHygiene() {
        List<Map<String, Object>> cases = loadCases();
        List<String> seen = new ArrayList<>();
        for (Map<String, Object> c : cases) {
            assertThat(c).containsKeys("text", "intent");
            String text = String.valueOf(c.get("text")).trim();
            assertThat(text).as("text 不能为空").isNotEmpty();
            assertThat(seen).as("说法重复：%s（同一句只留一条，避免 golden 虚胖）", text).doesNotContain(text);
            seen.add(text);
        }
    }
}
