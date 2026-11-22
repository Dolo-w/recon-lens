package io.github.dolow.reconlens.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 通用槽位协议的纯单元测试：不联网、不起库。
 *
 * <p>这一层是"一次一问 + 过期 + 补答只填参数"三条协议的落地处，
 * 协议错了的表现是「用户隔天回『好』触发了昨天的写操作」——比功能缺失严重得多，
 * 所以每条边界都钉死。
 */
class SlotProtocolTest {

    @Test
    @DisplayName("盖时间戳：拷贝原参数并带上 __at，不改调用方的 Map")
    void stamped拷贝并盖章() {
        Map<String, String> original = Map.of("question", "要重跑哪个任务？");
        Map<String, String> out = SlotProtocol.stamped(original);
        assertThat(out.get("question")).isEqualTo("要重跑哪个任务？");
        assertThat(out).containsKey(SlotProtocol.STAMP);
        assertThat(original).doesNotContainKey(SlotProtocol.STAMP);
        assertThat(SlotProtocol.stampedQuestion("看哪张表？")).containsEntry("question", "看哪张表？");
    }

    @Test
    @DisplayName("过期判定：新挂起不过期，15 分钟前挂起的过期")
    void 过期判定() {
        long now = System.currentTimeMillis();
        ChatSlots fresh = ChatSlots.EMPTY.withPending("RETRY", SlotProtocol.stampedQuestion("要重跑哪个任务？"));
        assertThat(SlotProtocol.pendingExpired(fresh, now)).isFalse();

        Map<String, String> staleArgs = Map.of(
                "question", "要重跑哪个任务？",
                SlotProtocol.STAMP, Long.toString(now - SlotProtocol.TTL_MS - 1000));
        ChatSlots stale = ChatSlots.EMPTY.withPending("RETRY", staleArgs);
        assertThat(SlotProtocol.pendingExpired(stale, now)).isTrue();
    }

    @Test
    @DisplayName("不过期的两种：没有时间戳的老挂起（向后兼容）、起草状态机")
    void 不过期的两种() {
        long now = System.currentTimeMillis();
        ChatSlots legacy = ChatSlots.EMPTY.withPending("RUN", Map.of("question", "要跑哪条规则？"));
        assertThat(SlotProtocol.pendingExpired(legacy, now)).isFalse();
        ChatSlots draft = ChatSlots.EMPTY.withPending(DraftIntake.ACTION,
                SlotProtocol.stampedQuestion("左侧用哪张表？"));
        assertThat(SlotProtocol.pendingExpired(draft, now - SlotProtocol.TTL_MS * 10)).isFalse();
    }

    @Test
    @DisplayName("补答：任务号类挂起认裸数字，EXPLAIN_DIFF 认『第 N 条』和裸数字")
    void fill填编号() {
        assertThat(SlotProtocol.fill("RETRY", "123")).isPresent()
                .get().extracting(call -> call.jobId()).isEqualTo(123L);
        assertThat(SlotProtocol.fill("JOB_SUMMARY", "123 号任务怎么样")).isPresent()
                .get().extracting(call -> call.jobId()).isEqualTo(123L);
        assertThat(SlotProtocol.fill("LIST_DIFFS", "任务 7")).isPresent()
                .get().extracting(call -> call.jobId()).isEqualTo(7L);
        assertThat(SlotProtocol.fill("EXPLAIN_DIFF", "第 137 条")).isPresent()
                .get().extracting(call -> call.diffId()).isEqualTo(137L);
        assertThat(SlotProtocol.fill("EXPLAIN_DIFF", "137")).isPresent()
                .get().extracting(call -> call.diffId()).isEqualTo(137L);
    }

    @Test
    @DisplayName("不是在补答：整句长过编号、没有数字、换了话题，一律不填")
    void fill拒绝不是答案的句子() {
        assertThat(SlotProtocol.fill("RETRY", "不是这个任务，我想看规则列表")).isEmpty();
        assertThat(SlotProtocol.fill("RETRY", "算了")).isEmpty();
        assertThat(SlotProtocol.fill("EXPLAIN_DIFF", "为什么差这么多")).isEmpty();
        // 长句里的数字不能当成编号填进去
        assertThat(SlotProtocol.fill("JOB_SUMMARY", "帮我看看前面 3 个任务里哪个差异最多，顺便给我讲讲原因")).isEmpty();
    }

    @Test
    @DisplayName("handles 只认编号类挂起：RUN/起草/确认都不走通用补答")
    void handles范围() {
        assertThat(SlotProtocol.handles("RETRY")).isTrue();
        assertThat(SlotProtocol.handles("EXPLAIN_DIFF")).isTrue();
        assertThat(SlotProtocol.handles("RUN")).isFalse();
        assertThat(SlotProtocol.handles(DraftIntake.ACTION)).isFalse();
        assertThat(SlotProtocol.handles("CONFIRM_ACTION")).isFalse();
        assertThat(SlotProtocol.handles(null)).isFalse();
    }
}
