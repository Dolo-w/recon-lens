package io.github.dolow.reconlens.web;

import io.github.dolow.reconlens.eval.EvalCase;
import io.github.dolow.reconlens.eval.EvalMetrics;
import io.github.dolow.reconlens.store.EvalCaseRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 回放集的入库与聚合。
 *
 * <p>这是"没有真实流量时也能闭环"的那一段：回放脚本把逐题记录 POST 进来，
 * 聚合函数从同一张表出数。真上线之后，聚合函数换个数据源（行为表）即可，口径不换。
 *
 * <p>为什么默认开、但可以关：写接口只碰 {@code recon_eval_case} 一张表，没有业务副作用；
 * 但生产环境若不想要这个入口，{@code --recon.eval.enabled=false} 即可让整个控制器消失。
 */
@RestController
@RequestMapping("/api/eval")
@ConditionalOnProperty(prefix = "recon.eval", name = "enabled", havingValue = "true", matchIfMissing = true)
public class EvalController {

    private final EvalCaseRepository repository;

    public EvalController(EvalCaseRepository repository) {
        this.repository = repository;
    }

    /** 写入一个批次（同批次重跑会覆盖，不叠加）。 */
    @PostMapping("/{batch}/cases")
    public ResponseEntity<Map<String, Object>> ingest(@PathVariable String batch,
                                                      @RequestBody List<EvalCase> cases) {
        if (batch == null || batch.isBlank() || batch.length() > 64) {
            throw new IllegalArgumentException("批次名不能为空且不超过 64 个字符");
        }
        int saved = repository.save(batch, cases);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("batch", batch);
        out.put("saved", saved);
        return ResponseEntity.ok(out);
    }

    /** 已有批次：给"拿哪个批次当基线"用。 */
    @GetMapping("/batches")
    public List<EvalCaseRepository.Batch> batches() {
        return repository.batches();
    }

    /** 一个批次的四项口径 + 通过率。 */
    @GetMapping("/{batch}/metrics")
    public EvalMetrics metrics(@PathVariable String batch) {
        List<EvalCase> cases = repository.findByBatch(batch);
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("没有这个评测批次：" + batch);
        }
        return EvalMetrics.of(cases);
    }

    /** 逐题明细：出 diff、看是哪几条退步时用。 */
    @GetMapping("/{batch}/cases")
    public List<EvalCase> cases(@PathVariable String batch) {
        return repository.findByBatch(batch);
    }

    @DeleteMapping("/{batch}")
    public Map<String, Object> delete(@PathVariable String batch) {
        return Map.of("batch", batch, "deleted", repository.delete(batch));
    }
}
