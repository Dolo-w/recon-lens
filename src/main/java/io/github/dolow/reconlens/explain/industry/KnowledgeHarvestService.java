package io.github.dolow.reconlens.explain.industry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dolow.reconlens.domain.IndustryKnowledge;
import io.github.dolow.reconlens.llm.LlmChatClient;
import io.github.dolow.reconlens.llm.LlmPurpose;
import io.github.dolow.reconlens.llm.PromptGuard;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 业界知识采集管道（全自动，人只看报表）：
 *
 * <ul>
 *   <li>{@link #distill} —— LLM 预训练行业常识蒸馏：按主题列举常见病因，起草成条目；</li>
 *   <li>{@link #ingest} —— 多源摄取：PDF / Markdown / HTML / 纯文本丢进来，
 *       代码层清洗（剥标签、去噪声、分块），LLM 提炼候选条目，机器准入后入库。</li>
 * </ul>
 *
 * <p>没有人工核证闸：条目带来源与初始置信度直接入库，错误知识由 K1 核销反馈的
 * 驳回信号自动降权淘汰（{@link IndustryKbService} 的生命周期）。图片暂不支持
 * （本机链路无 vision 模型，OCR 会引入不可控错字进先验）——如实拒绝，提示先转文字。
 */
@Service
public class KnowledgeHarvestService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeHarvestService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String DISTILL_SYSTEM = """
            你是清结算/对账领域的资深知识工程师。把给定资料提炼成「可用于对账差异归因的行业知识」条目。
            每条 = 适用条件 + 一句可执行的结论（遇到 X 类差异，先查 Y）。要求：
            1. text 一句话不超过 120 字，必须可执行，不要"注意金额准确性"这类废话；
            2. appliesWhen 条件尽量具体：diffTypes 常用 amount_mismatch / missing_left / missing_right /
               duplicate / pending_left / pending_right；fieldRegex 用字段名正则；directions 用 + / - / 0；
            3. 条件不能全空（无条件条目会对每条差异生效，等于常驻加税）；
            4. 只输出 JSON 数组，每项 {key, appliesWhen, text}，key 用 THEME-SLUG 形式；
               没有可提炼的内容输出 []。不要 markdown 代码块。
            收录红线（docs/industry-knowledge-criteria.md）：
            - 只收跨客户成立的普遍先验；客户特定配置、孤例事故不收（那是客户知识，不是行业先验）；
            - 不收精确数字断言（费率/上限/阈值会过时，且会被误当计算依据）——写排查方向不写具体数值；
            - 不收瞬时事实、教程、营销内容；与对账差异归因无关的通用知识不收；
            - 写法用概率语言（"通常/优先/先对齐口径"），只指排查方向，不做定论。

            判断对象是「条目」不是「资料」：资料本身可能是教程/API 文档/营销页，这不代表整块作废——
            要提炼的是其中承载的产品语义与状态机事实（某状态的真实含义、某操作的账务后果、口径规则）；
            被排除的是把操作步骤本身当条目。厂商官方计费/发票/钱包文档是最高等级来源，通常有可提炼内容；
            拿不准时偏向提炼，条目置信度由后续闭环反馈管理，不需要你在这一步保守。
            """;

    private final IndustryKbService service;
    private final LlmChatClient llm;

    public KnowledgeHarvestService(IndustryKbService service, LlmChatClient llm) {
        this.service = service;
        this.llm = llm;
    }

    public boolean available() {
        return llm.available();
    }

    /** LLM 预训练常识蒸馏。theme 如 "billing-pricing" / "recon-cutoff"。 */
    public HarvestReport distill(String theme, int maxItems) {
        if (!llm.available()) {
            return HarvestReport.failed("未配置模型 key，蒸馏不可用");
        }
        String user = "主题：" + theme + "，最多 " + maxItems + " 条。请列举该主题下对账差异的常见病因与排查结论。";
        return harvest(DISTILL_SYSTEM, user, null, IndustryKnowledge.Origin.HARVEST,
                "llm-common-" + slug(theme) + "-" + LocalDate.now());
    }

    /**
     * 多源摄取：按文件名后缀提取文本（pdf / md / html / htm / txt），清洗分块后逐块提炼。
     * 返回报告含每块产出。不支持图片/二进制——如实拒绝，不猜内容。
     */
    public HarvestReport ingest(String fileName, byte[] raw) {
        // 先做确定性的类型/解析校验，再查 key——报错必须指向真正的问题
        //（否则没 key 时丢一张图片进来，得到的却是"未配置模型 key"，把人引去配 key 而不是转格式）
        String lower = fileName.toLowerCase(Locale.ROOT);
        String text;
        try {
            if (lower.endsWith(".pdf")) {
                text = extractPdf(raw);
            } else if (lower.endsWith(".md") || lower.endsWith(".txt") || lower.endsWith(".csv")) {
                text = new String(raw, StandardCharsets.UTF_8);
            } else if (lower.endsWith(".html") || lower.endsWith(".htm")) {
                text = cleanHtml(new String(raw, StandardCharsets.UTF_8));
            } else if (isImage(lower) || lower.endsWith(".docx") || lower.endsWith(".doc")
                    || lower.endsWith(".xlsx") || lower.endsWith(".xls") || lower.endsWith(".pptx")) {
                return HarvestReport.failed("暂不支持 " + fileName + "：图片需先转文字（本机链路无 OCR），"
                        + "office 文档请另存为 PDF 或纯文本");
            } else {
                return HarvestReport.failed("不认识的文件类型: " + fileName);
            }
        } catch (IOException e) {
            return HarvestReport.failed("文件解析失败: " + e.getMessage());
        }
        if (!llm.available()) {
            return HarvestReport.failed("未配置模型 key，摄取不可用");
        }
        return ingestText(fileName, text);
    }

    private HarvestReport ingestText(String fileName, String text) {
        String source = slug(fileName) + "-" + LocalDate.now();
        List<String> chunks = chunk(cleanWhitespace(text), 1800);
        int created = 0;
        int updated = 0;
        int rejected = 0;
        List<String> keys = new ArrayList<>();
        for (String chunk : chunks) {
            if (chunk.length() < 50) {
                continue;
            }
            String user = "来源文件：" + fileName + "\n\n资料片段：\n"
                    + PromptGuard.dataBlockTokens("source", chunk, 2400);
            HarvestReport r = harvest(DISTILL_SYSTEM, user, null,
                    IndustryKnowledge.Origin.HARVEST, source);
            created += r.created();
            updated += r.updated();
            rejected += r.rejected();
        }
        return new HarvestReport(created, updated, rejected, keys, null);
    }

    /** 蒸馏/摄取共用：调 LLM → 解析条目 → 机器准入 → 入库。autoKey 时自动生成 entry_key。 */
    private HarvestReport harvest(String system, String user, String autoKey,
                                  IndustryKnowledge.Origin origin, String source) {
        Optional<String> content = llm.complete(LlmPurpose.HARVEST, system, user);
        if (content.isEmpty()) {
            return HarvestReport.failed("模型调用无返回");
        }
        List<IndustryKnowledge> candidates = parseEntries(content.get(), origin, source, autoKey);
        int created = 0;
        int updated = 0;
        int rejected = 0;
        List<String> keys = new ArrayList<>();
        for (IndustryKnowledge candidate : candidates) {
            IndustryKbService.AdmitResult result = service.admit(candidate);
            switch (result.outcome()) {
                case CREATED -> {
                    created++;
                    keys.add(result.entry().entryKey());
                }
                case UPDATED -> {
                    updated++;
                    keys.add(result.entry().entryKey());
                }
                case REJECTED -> {
                    rejected++;
                    log.debug("采集候选被准入闸拒绝: {} — {}", candidate.entryKey(), result.rejection());
                }
            }
        }
        return new HarvestReport(created, updated, rejected, keys, null);
    }

    private List<IndustryKnowledge> parseEntries(String json, IndustryKnowledge.Origin origin,
                                                 String source, String autoKey) {
        List<IndustryKnowledge> out = new ArrayList<>();
        String cleaned = json.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("```\\s*$", "").trim();
        }
        int start = cleaned.indexOf('[');
        int end = cleaned.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return out;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(cleaned.substring(start, end + 1));
        } catch (Exception e) {
            log.warn("采集输出解析失败: {}", e.toString());
            return out;
        }
        int seq = 0;
        for (JsonNode node : root) {
            try {
                JsonNode when = node.path("appliesWhen");
                IndustryKnowledge.AppliesWhen applies = new IndustryKnowledge.AppliesWhen(
                        toStringList(when.path("ruleCodes")),
                        toStringList(when.path("diffTypes")),
                        when.path("fieldRegex").asText(null),
                        toStringList(when.path("directions")));
                // 模型给了合法 key 就尊重（key 是稳定锚点），缺失时才用 autoKey/时间戳兜底
                String modelKey = node.path("key").asText("");
                String key = !modelKey.isBlank() && modelKey.length() <= 96 ? modelKey
                        : autoKey != null ? autoKey + "-" + ++seq
                        : "AI-" + System.currentTimeMillis() + "-" + ++seq;
                out.add(new IndustryKnowledge(null, key, applies,
                        node.path("text").asText(), source, origin,
                        origin.initialConfidence(), IndustryKnowledge.Status.ACTIVE, 0, 0, null));
            } catch (RuntimeException e) {
                log.debug("采集条目解析跳过: {}", e.toString());
            }
        }
        return out;
    }

    // ---------- 清洗（确定性代码层：目标是省 token 与去噪声，不追求完美） ----------

    private String extractPdf(byte[] raw) throws IOException {
        try (PDDocument doc = Loader.loadPDF(raw)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(doc);
        }
    }

    /** HTML 清洗：剥 script/style 块与全部标签 + 常见实体解码。 */
    static String cleanHtml(String html) {
        String s = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?is)<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'");
        return s;
    }

    /** 归一空白：\r 去掉、三连以上空白压成一个换行。 */
    static String cleanWhitespace(String text) {
        return text.replace('\r', ' ')
                .replaceAll("[ \\t]{2,}", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    /** 按段落聚合分块：块内 ≤maxChars，标题行（短行）优先开新块。 */
    static List<String> chunk(String text, int maxChars) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String para : text.split("\n")) {
            if (cur.length() + para.length() + 1 > maxChars && cur.length() > 0) {
                out.add(cur.toString().trim());
                cur.setLength(0);
            }
            cur.append(para).append('\n');
        }
        if (cur.length() > 0) {
            out.add(cur.toString().trim());
        }
        return out;
    }

    private static boolean isImage(String lower) {
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp");
    }

    static String slug(String s) {
        return s == null ? "x" : s.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\u4e00-\\u9fa5]+", "-")
                .replaceAll("^-+|-+$", "");
    }

    private static List<String> toStringList(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array.isArray()) {
            array.forEach(n -> out.add(n.asText()));
        }
        return out;
    }

    /** 采集报告：条目数与 key 清单；failed 时 error 非空。 */
    public record HarvestReport(int created, int updated, int rejected, List<String> keys, String error) {
        static HarvestReport failed(String reason) {
            return new HarvestReport(0, 0, 0, List.of(), reason);
        }

        public boolean failed() {
            return error != null;
        }
    }
}
