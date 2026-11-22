package io.github.dolow.reconlens.explain.industry;

import io.github.dolow.reconlens.domain.IndustryKnowledge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    /** YAML 解析用 SnakeYAML：spring-boot 自带（application.yml 就是它解析的），零新增依赖面。 */
    private final Yaml yamlParser = new Yaml();

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

    List<String> loadAll() {
        List<String> yamls = new ArrayList<>();
        try {
            // PathMatchingResourcePatternResolver 而非 getFile()：后者在 jar 内对目录资源必炸，
            // 症状就是"本地跑有种子、生产 jar 里静默归零"
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            for (Resource resource : resolver.getResources("classpath*:patterns/industry-knowledge/*.yml")) {
                try (InputStream in = resource.getInputStream()) {
                    yamls.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        } catch (IOException e) {
            // classpath 种子不可达：不致命，外部目录还有机会
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

    /** 解析 seed YAML 为候选条目（SnakeYAML：Jackson readTree 不认识 YAML，曾让 24 条种子静默归零）。 */
    List<IndustryKnowledge> parse(String yaml) {
        List<IndustryKnowledge> entries = new ArrayList<>();
        Object root;
        try {
            root = yamlParser.load(yaml);
        } catch (RuntimeException e) {
            log.warn("种子 YAML 解析失败，整文件跳过: {}", e.toString());
            return entries;
        }
        if (!(root instanceof Map<?, ?> rootMap) || !(rootMap.get("entries") instanceof List<?> list)) {
            return entries;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> node)) {
                continue;
            }
            try {
                Map<?, ?> when = node.get("appliesWhen") instanceof Map<?, ?> w ? w : Map.of();
                IndustryKnowledge.AppliesWhen applies = new IndustryKnowledge.AppliesWhen(
                        toStringList(when.get("ruleCodes")),
                        toStringList(when.get("diffTypes")),
                        when.get("fieldRegex") == null ? null : String.valueOf(when.get("fieldRegex")),
                        toStringList(when.get("directions")));
                entries.add(new IndustryKnowledge(null, text(node.get("key")),
                        applies, text(node.get("text")), text(node.get("source")),
                        IndustryKnowledge.Origin.SEED,
                        IndustryKnowledge.Origin.SEED.initialConfidence(),
                        IndustryKnowledge.Status.ACTIVE, 0, 0, null));
            } catch (RuntimeException e) {
                log.warn("种子条目解析失败，跳过: {} — {}", node.get("key"), e.toString());
            }
        }
        return entries;
    }

    private static String text(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static List<String> toStringList(Object o) {
        if (o instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }
}
