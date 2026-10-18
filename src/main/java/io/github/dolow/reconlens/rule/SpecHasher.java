package io.github.dolow.reconlens.rule;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 规则内容哈希。<b>唯一出处</b>：审批绑定（{@code spec_sha256}）与"内容被改过"检测都用它，
 * 不允许在别处再写一份"把规则转成字符串"的逻辑 —— 两份算法迟早会分叉，那时审批链就断了。
 *
 * <p>哈希的对象是落库的 {@code spec_json} 原文，不是重新序列化的 {@link io.github.dolow.reconlens.dsl.RuleSpec}：
 * 只有原文才能证明"库里这一行没有被 DBA 直接 UPDATE 过"。
 */
public final class SpecHasher {

    private SpecHasher() {
    }

    public static String sha256(String json) {
        if (json == null) {
            throw new IllegalArgumentException("规则原文为空，无法计算 spec_sha256");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }
}
