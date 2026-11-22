package io.github.dolow.reconlens.eval;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 {@code eval/tasks.yml} 读成 {@link EvalSuiteRunner.Task} 列表。
 *
 * <p>用 SnakeYAML 而不是 Jackson：{@code tasks.yml} 里有中文注释与块结构，
 * Jackson 不解析 YAML（这个坑在 K5 种子导入时已经踩过一次，症状是"整文件被静默跳过"）。
 *
 * <p>解析失败<b>直接抛</b>，不返回空列表：回归集读成 0 条然后"全部通过"是
 * 最坏的一种假绿（{@code IntentGoldenTest} 的防空集纪律同理）。
 */
public final class EvalTaskLoader {

    private EvalTaskLoader() {
    }

    public static List<EvalSuiteRunner.Task> loadFromClasspath(String resource) {
        try (InputStream in = EvalTaskLoader.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("找不到 eval 用例文件: " + resource);
            }
            return parse(new Yaml().load(in));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("读取 eval 用例失败: " + resource, e);
        }
    }

    @SuppressWarnings("unchecked")
    static List<EvalSuiteRunner.Task> parse(Object root) {
        if (!(root instanceof Map<?, ?> map)) {
            throw new IllegalStateException("eval 用例文件顶层必须是 map，实际: "
                    + (root == null ? "null" : root.getClass().getSimpleName()));
        }
        Object rawTasks = map.get("tasks");
        if (!(rawTasks instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalStateException("eval 用例文件的 tasks 必须是非空列表 —— "
                    + "空列表等于零覆盖，绝不能当成通过");
        }

        List<EvalSuiteRunner.Task> tasks = new ArrayList<>(list.size());
        for (Object item : list) {
            Map<String, Object> t = (Map<String, Object>) item;
            String key = str(t.get("key"));
            String rule = str(t.get("rule"));
            if (key.isBlank() || rule.isBlank()) {
                throw new IllegalStateException("用例缺少 key 或 rule: " + t);
            }
            tasks.add(new EvalSuiteRunner.Task(
                    key,
                    str(t.getOrDefault("title", key)),
                    rule,
                    rows(t.get("left")),
                    rows(t.get("right")),
                    expect(t.get("expect"))));
        }
        return tasks;
    }

    @SuppressWarnings("unchecked")
    private static EvalSuiteRunner.Expect expect(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalStateException("用例缺少 expect 段：没有期望的回归用例只能看不能判");
        }
        long total = map.get("total") instanceof Number n ? n.longValue() : 0L;
        Map<String, Long> types = new LinkedHashMap<>();
        if (map.get("types") instanceof Map<?, ?> t) {
            for (Map.Entry<?, ?> e : t.entrySet()) {
                types.put(String.valueOf(e.getKey()),
                        e.getValue() instanceof Number n ? n.longValue() : 0L);
            }
        }
        // 显式写了 total 就以 total 为准；没写则由 types 求和推出，避免两处不一致
        if (!map.containsKey("total")) {
            total = types.values().stream().mapToLong(Long::longValue).sum();
        }
        return new EvalSuiteRunner.Expect(total, types);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Object raw) {
        if (raw == null) {
            return List.of(); // 允许空侧：左空 / 右空是重要的边界用例
        }
        if (!(raw instanceof List<?> list)) {
            throw new IllegalStateException("left/right 必须是列表，实际: " + raw);
        }
        List<Map<String, Object>> rows = new ArrayList<>(list.size());
        for (Object o : list) {
            rows.add((Map<String, Object>) o);
        }
        return rows;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
