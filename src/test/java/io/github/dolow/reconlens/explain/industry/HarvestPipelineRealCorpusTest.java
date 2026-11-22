package io.github.dolow.reconlens.explain.industry;

import io.github.dolow.reconlens.domain.IndustryKnowledge;
import io.github.dolow.reconlens.llm.LlmChatClient;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 真实语料管道首验（2026-11-22）：harvest-test/ 目录下的 Lago 官方文档（A 级），
 * 用替身 LLM 走完 ingest 全链路（读取 → 清洗 → 分块 → 逐块 LLM 调用 → 解析 → 机器准入 → 计数）。
 *
 * <p>验证的是<b>管道</b>，不是模型产出质量——替身 LLM 返回固定 JSON（每块 2 条可入库 + 1 条
 * 无条件的红线候选），断言计数关系与红线拦截。蒸馏质量对照基准在 harvest-test/golden-expected.md
 * （人工蒸馏），配真 key 跑完后人工对照评分。
 *
 * <p>CI 上没有本机语料目录 → assumeTrue skip（skip 原因打印，不是假绿）。
 */
class HarvestPipelineRealCorpusTest {

    private static final Path CORPUS = Path.of("harvest-test");
    private static final List<String> CORPUS_FILES = List.of(
            "lago-invoice-void.md", "lago-draft-invoice.md", "lago-wallet-topup-void.md",
            "lago-invoicing.md", "lago-wallets.md");

    /** 替身 LLM 固定返回：2 条合格候选 + 1 条无条件的红线候选（准入应拒）。 */
    private static final String FAKE_LLM_JSON = """
            [
              {"key":"LAGO-PIPE-TEST-A","appliesWhen":{"fieldRegex":".*invoice.*"},
               "text":"作废发票报表价值为零且不可支付，状态为终态"},
              {"key":"LAGO-PIPE-TEST-B","appliesWhen":{"diffTypes":["missing_left","missing_right"]},
               "text":"draft 发票不是应收账款，先确认宽限期再查漏单"},
              {"key":"AI-UNCONDITIONAL-REDLINE","appliesWhen":{},
               "text":"客户专属配置示例条目，无条件会被准入拒绝"}
            ]
            """;

    @BeforeAll
    static void requireLocalCorpus() {
        Assumptions.assumeTrue(Files.isDirectory(CORPUS),
                "本机 harvest-test/ 语料目录不存在（CI 跳过，非假绿）");
    }

    @Test
    void everyCorpusFileFlowsThroughPipeline() throws Exception {
        IndustryKbService service = admitService();
        for (String fileName : CORPUS_FILES) {
            AtomicInteger llmCalls = new AtomicInteger();
            KnowledgeHarvestService harvester = new KnowledgeHarvestService(service, fakeLlm(llmCalls));
            byte[] raw = Files.readAllBytes(CORPUS.resolve(fileName));

            KnowledgeHarvestService.HarvestReport r = harvester.ingest(fileName, raw);

            assertNotNull(r.error() == null ? "ok" : null, fileName + " 不应失败");
            assertTrue(r.created() >= 2, fileName + " 至少一块产出 2 条");
            int blocks = llmCalls.get();
            assertTrue(blocks >= 1, fileName + " 至少一次 LLM 调用");
            assertEquals(blocks * 2, r.created(), fileName + " created 应等于块数×2");
            assertEquals(blocks, r.rejected(), fileName + " 红线候选应每块被拒 1 条");
            assertTrue(r.keys().stream().allMatch(k -> k.startsWith("LAGO-PIPE-TEST-")),
                    fileName + " 入库 key 应全部来自替身 LLM");
        }
    }

    @Test
    void chunkingReportForRealCorpus() throws Exception {
        // 分块质量观察（人工看测试输出）：块数 / 平均块长 / 被过滤的短块数
        for (String fileName : CORPUS_FILES) {
            String text = Files.readString(CORPUS.resolve(fileName), StandardCharsets.UTF_8);
            String cleaned = KnowledgeHarvestService.cleanWhitespace(text);
            List<String> chunks = KnowledgeHarvestService.chunk(cleaned, 1800);
            int effective = (int) chunks.stream().filter(c -> c.length() >= 50).count();
            int filtered = chunks.size() - effective;
            int avg = effective == 0 ? 0
                    : (int) chunks.stream().filter(c -> c.length() >= 50)
                            .mapToInt(String::length).average().orElse(0);
            System.out.printf("[corpus] %s: 原文 %d 字 -> %d 块（有效 %d，短块过滤 %d），有效块平均 %d 字%n",
                    fileName, text.length(), chunks.size(), effective, filtered, avg);
            assertTrue(effective >= 1, fileName + " 至少一个有效分块");
        }
    }

    @Test
    void ingestRejectsUnknownTypesHonestly() {
        KnowledgeHarvestService harvester = new KnowledgeHarvestService(
                mock(IndustryKbService.class), mock(LlmChatClient.class));
        KnowledgeHarvestService.HarvestReport r = harvester.ingest("screenshot.png", new byte[]{1, 2, 3});
        assertNotNull(r.error(), "图片应如实拒绝而非瞎猜");
        assertTrue(r.error().contains("OCR"), "拒绝理由应说明限制: " + r.error());
    }

    private static LlmChatClient fakeLlm(AtomicInteger calls) {
        LlmChatClient llm = mock(LlmChatClient.class);
        when(llm.available()).thenReturn(true);
        when(llm.complete(anyString(), anyString(), anyString())).thenAnswer(inv -> {
            calls.incrementAndGet();
            return Optional.of(FAKE_LLM_JSON);
        });
        return llm;
    }

    private static IndustryKbService admitService() {
        IndustryKbService service = mock(IndustryKbService.class);
        when(service.admit(any())).thenAnswer(inv -> {
            IndustryKnowledge e = inv.getArgument(0);
            IndustryKnowledge.AppliesWhen w = e.appliesWhen();
            boolean unconditional = w.fieldRegex() == null
                    && (w.diffTypes() == null || w.diffTypes().isEmpty())
                    && (w.ruleCodes() == null || w.ruleCodes().isEmpty());
            return unconditional
                    ? IndustryKbService.AdmitResult.rejected("无条件条目（红线）")
                    : IndustryKbService.AdmitResult.created(e);
        });
        return service;
    }
}
