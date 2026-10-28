package io.github.dolow.reconlens.store;

import io.github.dolow.reconlens.eval.EvalCase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * 评测逐题记录的存取。
 *
 * <p>为什么不用 {@code recon_agent_step} 直接替代：轨迹是"执行过程"，评测记录是
 * "一道题的对错"。前者由对账/起草产生，后者由回放脚本产生，混在一张表里，
 * 一次金标回归就会把生产轨迹的统计口径污染掉。
 */
@Repository
public class EvalCaseRepository {

    private final JdbcTemplate jdbc;

    public EvalCaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 一个批次一次写入（批次内先删后插，重跑同一批次不会叠加）。 */
    public int save(String batch, List<EvalCase> cases) {
        if (cases == null || cases.isEmpty()) {
            return 0;
        }
        jdbc.update("DELETE FROM recon_eval_case WHERE batch = ?", batch);
        List<Object[]> rows = new ArrayList<>(cases.size());
        for (EvalCase c : cases) {
            rows.add(new Object[]{batch, c.no(), c.question(), c.expectedIntent(), c.actualIntent(),
                    c.expectedTool(), c.actualTool(), c.turns(), c.multi(), c.costMs(), c.tokensEst(),
                    c.passed()});
        }
        jdbc.batchUpdate("""
                INSERT INTO recon_eval_case (batch, case_no, question, expected_intent, actual_intent,
                                             expected_tool, actual_tool, turns, multi, cost_ms, tokens_est,
                                             passed)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, rows);
        return rows.size();
    }

    public List<EvalCase> findByBatch(String batch) {
        return jdbc.query("""
                SELECT case_no, question, expected_intent, actual_intent, expected_tool, actual_tool,
                       turns, multi, cost_ms, tokens_est, passed
                FROM recon_eval_case WHERE batch = ? ORDER BY case_no
                """, (rs, i) -> new EvalCase(
                rs.getInt("case_no"), rs.getString("question"),
                rs.getString("expected_intent"), rs.getString("actual_intent"),
                rs.getString("expected_tool"), rs.getString("actual_tool"),
                rs.getInt("turns"), rs.getBoolean("multi"), rs.getInt("cost_ms"), rs.getInt("tokens_est"),
                rs.getBoolean("passed")), batch);
    }

    /** 已有批次 + 题数 + 最近写入时间，给"有没有可对比的基线"用。 */
    public List<Batch> batches() {
        return jdbc.query("""
                SELECT batch, COUNT(*) AS cases, MAX(created_at) AS last_at
                FROM recon_eval_case GROUP BY batch ORDER BY last_at DESC
                """, (rs, i) -> new Batch(rs.getString("batch"), rs.getInt("cases"),
                rs.getTimestamp("last_at")));
    }

    public int delete(String batch) {
        return jdbc.update("DELETE FROM recon_eval_case WHERE batch = ?", batch);
    }

    public record Batch(String name, int cases, Timestamp lastAt) {
    }


}

