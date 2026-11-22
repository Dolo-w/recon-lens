package io.github.dolow.reconlens.chat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * fallback 语料挖掘 —— 「越用越准」在路由层的最后一公里。
 *
 * <p>业界做法（2026 共识）：no-match 队列每周聚类 → 人工标注 → 新意图在它变成
 * 5% 流量之前浮现。本服务是它的<b>最小可用版</b>：把 {@code recon_chat_fallback}
 * 里收到的「没听懂」原话按<b>路由归一化</b>（{@link ChatIntentRouter#canonical}，
 * 和词表通道同一套同义替换）分组、按频次排序，交给人工扫一眼——
 * 哪个说法反复出现，就说明词表缺一条 CANON 或缺一个档位。
 *
 * <p><b>为什么不用 embedding 聚类</b>：当前语料规模（自用工具，月级别百条）
 * 用归一化分组已经能压掉绝大多数同义变体；等语料大到归一化压不住的时候，
 * 再上向量不迟——这是刻意的简单，不是遗漏。
 *
 * <p><b>出口接 golden 回归集</b>：确认的新说法按输出的 YAML 片段粘进
 * {@code src/test/resources/golden/intent-golden.yml}，从此这条说法被测试
 * 卡住——路由的每次改动（CANON、档位顺序、正则）跑歪了它都会翻红。
 * 挖掘发现 → 人工确认 → 进 golden → 卡住回归，闭环到这里才算合上。
 */
public class FallbackMiningService {

    private static final Logger log = LoggerFactory.getLogger(FallbackMiningService.class);
    /** 一次最多往回捞多少条原始语料：全量分组在 Java 侧做，规模到不了 SQL 分组的必要。 */
    private static final int SCAN_LIMIT = 2000;
    /** 每组最多带几条原句示例：给人工看的，不是导出全量。 */
    private static final int MAX_EXAMPLES = 3;

    private final JdbcTemplate jdbc;

    public FallbackMiningService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 一个候选组：同一归一化键下的若干「没听懂」原话。 */
    public record Group(String key, long count, List<String> examples) {
        /** 可直接粘进 intent-golden.yml 的片段：intent 留 TODO，人工填。 */
        public String goldenSnippet() {
            return "  - text: \"" + examples.get(0).replace("\"", "'") + "\"\n"
                    + "    intent: TODO  # 出现 " + count + " 次，归一化键: " + key;
        }
    }

    /**
     * 挖掘候选：归一化分组、频次降序。
     * 表不存在/库挂了返回空列表——挖掘是旁路，不能把 stats 页面带炸。
     */
    public List<Group> mine(int minCount, int limit) {
        List<String> texts = recentTexts();
        if (texts.isEmpty()) {
            return List.of();
        }
        // 归一化分组：canonical 和词表通道同一套同义替换——「账单对不上」「账单不平」
        // 归成一组，频次才反映真实痛点次数，而不是说法数量。
        Map<String, Group> byKey = new LinkedHashMap<>();
        for (String text : texts) {
            String key = ChatIntentRouter.canonical(text.toLowerCase(Locale.ROOT)).trim();
            if (key.isEmpty()) {
                key = text.trim();
            }
            Group g = byKey.get(key);
            if (g == null) {
                List<String> examples = new ArrayList<>();
                examples.add(text);
                byKey.put(key, new Group(key, 1, examples));
            } else {
                Group grown = new Group(g.key(), g.count() + 1, g.examples());
                if (grown.examples().size() < MAX_EXAMPLES) {
                    grown.examples().add(text);
                }
                byKey.put(key, grown);
            }
        }
        return byKey.values().stream()
                .filter(g -> g.count() >= Math.max(1, minCount))
                .sorted((a, b) -> Long.compare(b.count(), a.count()))
                .limit(Math.max(1, limit))
                .toList();
    }

    /** 全部候选的可粘贴 YAML：确认一条粘一条，粘完跑测试验证词表已经认识它。 */
    public String goldenYaml(int minCount, int limit) {
        List<Group> groups = mine(minCount, limit);
        if (groups.isEmpty()) {
            return "# 暂无候选（语料不足或都已沉淀）";
        }
        StringBuilder sb = new StringBuilder("# 以下为 fallback 挖掘候选，intent 需人工确认后填入\n");
        for (Group g : groups) {
            sb.append(g.goldenSnippet()).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private List<String> recentTexts() {
        if (jdbc == null) {
            return List.of();
        }
        try {
            return jdbc.queryForList(
                    "SELECT query_text FROM recon_chat_fallback ORDER BY created_at DESC, id DESC LIMIT "
                            + SCAN_LIMIT,
                    String.class);
        } catch (RuntimeException e) {
            log.warn("fallback 语料读取失败（老库缺 recon_chat_fallback 表？按空处理）", e);
            return List.of();
        }
    }
}
