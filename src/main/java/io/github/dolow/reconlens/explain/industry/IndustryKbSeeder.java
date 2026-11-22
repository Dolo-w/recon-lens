package io.github.dolow.reconlens.explain.industry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dolow.reconlens.domain.IndustryKnowledge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 业界知识种子导入：classpath 的 {@code patterns/industry-knowledge/*.yml} +
 * 外部目录（{@code recon.explain.industry.dir}，运维/客户侧投放）。启动时跑一次，
 * 同 entry_key 幂等 upsert（文本没变是无害重放）。
 *
 * <p>放 ApplicationRunner 不放构造器：等上下文与 schema 初始化都完成后才碰库。
 * 坏文件跳过不挡启动——种子是便利不是依赖。
 */
@Component
public class IndustryKbSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IndustryKbSeeder.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final IndustryKbService service;
    private final String externalDir;

    public IndustryKbSeeder(IndustryKbService service,
                            @Value("${recon.explain.industry.dir:}") String externalDir) {
        this.service = service;
        this.externalDir = externalDir;
    }

    @Override
    public void run(ApplicationArguments args) {
        int created = 0;
        int updated = 0;
        int skipped = 0;
        for (String yaml : loadAll()) {
            for (IndustryKnowledge e : parse(yaml)) {
                IndustryKbService.AdmitResult result = service.admit(e);
                if (result.outcome() == IndustryKbService.AdmitResult.Outcome.CREATED) {
                    created++;
                } else if (result.outcome() == IndustryKbService.AdmitResult.Outcome.UPDATED) {
                    updated++;
                } else {
                    skipped++;
                }
            }
        }
        if (created + updated + skipped > 0) {
            log.info("业界知识种子导入完成: 新增 {} / 更新 {} / 拒绝 {}", created, updated, skipped);
        }
    }

    private List<String> loadAll() {
        List<String> yamls = new ArrayList<>();
        try {
            Path cpDir = new ClassPathResource("patterns/industry-knowledge").getFile().toPath();
            collect(yamls, cpDir);
        } catch (IOException e) {
            // classpath 目录不可达（打成 jar 后 getFile 失败）：不致命，外部目录还有机会
            log.debug("classpath 种子目录不可达: {}", e.toString());
        }
        if (externalDir != null && !externalDir.isBlank()) {
            Path ext = Path.of(externalDir);
            if (Files.isDirectory(ext)) {
                collect(yamls, ext);
            }
        }
        return yamls;
    }

    private void collect(List<String> into, Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.toString().endsWith(".yml")).sorted().forEach(p -> {
                try {
                    into.add(Files.readString(p, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    log.warn("种子文件读取失败，跳过: {} — {}", p, e.toString());
                }
            });
        } catch (IOException e) {
            log.warn("种子目录遍历失败，跳过: {} — {}", dir, e.toString());
        }
    }

    /** 解析 seed YAML 为候选条目（手动解析极简结构，避免为 seeds 引入 snakeyaml 依赖面）。 */
    List<IndustryKnowledge> parse(String yaml) {
        List<IndustryKnowledge> entries = new ArrayList<>();
        JsonNode root;
        try {
            root = MAPPER.readTree(yaml);
        } catch (IOException e) {
            log.warn("种子 YAML 解析失败，整文件跳过: {}", e.toString());
            return entries;
        }
        for (JsonNode node : root.path("entries")) {
            try {
                JsonNode when = node.path("appliesWhen");
                IndustryKnowledge.AppliesWhen applies = new IndustryKnowledge.AppliesWhen(
                        toStringList(when.path("ruleCodes")),
                        toStringList(when.path("diffTypes")),
                        when.path("fieldRegex").asText(null),
                        toStringList(when.path("directions")));
                entries.add(new IndustryKnowledge(null, node.path("key").asText(),
                        applies, node.path("text").asText(), node.path("source").asText(),
                        IndustryKnowledge.Origin.SEED,
                        IndustryKnowledge.Origin.SEED.initialConfidence(),
                        IndustryKnowledge.Status.ACTIVE, 0, 0, null));
            } catch (RuntimeException e) {
                log.warn("种子条目解析失败，跳过: {} — {}", node.path("key").asText("?"), e.toString());
            }
        }
        return entries;
    }

    private static List<String> toStringList(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array.isArray()) {
            array.forEach(n -> out.add(n.asText()));
        }
        return out;
    }
}
