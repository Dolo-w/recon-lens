package io.github.dolow.reconlens.explain.industry;

import io.github.dolow.reconlens.domain.IndustryKnowledge;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 防空集假绿：直接加载真实 classpath seed.yml 走真实解析链路。
 * 背景：parse 一度用 Jackson readTree 解析 YAML（不认识），24 条种子静默归零，
 * 而旧测试喂的是 JSON 风格样例（JSON 是 YAML 子集）所以全绿——真实文件从未被测过。
 */
class IndustryKbSeederSeedFileTest {

    private IndustryKbSeeder seeder() {
        IndustryKbService service = mock(IndustryKbService.class);
        when(service.admit(any())).thenAnswer(inv -> IndustryKbService.AdmitResult.created(inv.getArgument(0)));
        return new IndustryKbSeeder(service, "");
    }

    @Test
    void realSeedFileParsesToFullCatalog() throws Exception {
        String yaml = new ClassPathResource("patterns/industry-knowledge/seed.yml")
                .getContentAsString(StandardCharsets.UTF_8);
        List<IndustryKnowledge> entries = seeder().parse(yaml);
        // 低于 20 条 = 种子语料被吞了，启动链路必有回归
        assertTrue(entries.size() >= 20, "真实 seed.yml 应解析出 >=20 条，实际 " + entries.size());
    }

    @Test
    void everySeedEntryAdmissible() throws Exception {
        String yaml = new ClassPathResource("patterns/industry-knowledge/seed.yml")
                .getContentAsString(StandardCharsets.UTF_8);
        List<IndustryKnowledge> entries = seeder().parse(yaml);
        for (IndustryKnowledge e : entries) {
            assertNotNull(e.entryKey());
            assertFalse(e.entryKey().isBlank(), "key 不能为空");
            assertFalse(e.text().isBlank(), "text 不能为空: " + e.entryKey());
            assertFalse(e.source().isBlank(), "source 不能为空: " + e.entryKey());
            // 准入纪律：无条件条目 = 对每条差异注入 = 常驻加税，机器准入会拒绝
            IndustryKnowledge.AppliesWhen w = e.appliesWhen();
            boolean hasCondition = (w.ruleCodes() != null && !w.ruleCodes().isEmpty())
                    || (w.diffTypes() != null && !w.diffTypes().isEmpty())
                    || (w.fieldRegex() != null && !w.fieldRegex().isBlank())
                    || (w.directions() != null && !w.directions().isEmpty());
            assertTrue(hasCondition, "条目缺少适用条件，会被准入拒绝: " + e.entryKey());
        }
    }

    @Test
    void seedEntriesLoadFromRealClasspathLoader() {
        // 端到端：loadAll（resolver 通道）+ parse 全链，classpath 下至少吃到 seed.yml
        List<String> yamls = seeder().loadAll();
        assertTrue(yamls.stream().anyMatch(y -> y.contains("IND-ROUNDING-SUBCENT")),
                "classpath loader 应能读到真实 seed.yml 内容");
    }
}
