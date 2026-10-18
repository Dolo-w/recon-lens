package io.github.dolow.reconlens;

import io.github.dolow.reconlens.dsl.RuleParser;
import io.github.dolow.reconlens.dsl.RuleSpec;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/** 单测共用的规则样例：4 分片、金额容差、状态忽略大小写。 */
public final class TestRules {

    public static final String YAML = """
            code: T_BILL
            name: 测试规则
            left:
              label: 账单
              table: bill_left
              keyField: order_no
              keyType: STRING
              orderBy: order_no
            right:
              label: 结算
              table: bill_right
              keyField: order_no
              keyType: STRING
              orderBy: order_no
            shard:
              count: 4
              parallelism: 2
              whereLeft: "MOD(CAST(SUBSTRING(order_no, 10, 2) AS SIGNED), {shards}) = {index}"
              whereRight: "MOD(CAST(SUBSTRING(order_no, 10, 2) AS SIGNED), {shards}) = {index}"
            compare:
              - field: amount
                type: DECIMAL
                tolerance: 0.01
                diffType: AMOUNT_MISMATCH
                severity: HIGH
              - field: status
                type: STRING
                ignoreCase: true
                diffType: STATUS_MISMATCH
                severity: MEDIUM
            missing:
              leftMissingDiffType: MISSING_LEFT
              rightMissingDiffType: MISSING_RIGHT
              severity: HIGH
            duplicate:
              diffType: DUPLICATE_KEY
              severity: MEDIUM
            options:
              maxAttempts: 2
            """;

    private TestRules() {
    }

    public static RuleSpec bill() {
        return new RuleParser().parse(
                new ByteArrayInputStream(YAML.getBytes(StandardCharsets.UTF_8)), "test.yml");
    }
}
