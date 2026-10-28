package io.github.dolow.reconlens.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.dolow.reconlens.store.ReconRepository;
import io.github.dolow.reconlens.store.RuleRepository;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * 系统事实缓存：证明"同一份目录两次只拼一次"，以及三种该重建的情况都真的重建了。
 *
 * <p>这里用 mock 的两个仓库而不是真库：要断言的是"调了几次 builder"，
 * 真库反而看不清——H2 上没有直接的办法数"这个类查了几次库"。
 */
class FactsCacheTest {

    private final RuleRepository rules = mock(RuleRepository.class);
    private final ReconRepository repository = mock(ReconRepository.class);

    @Test
    void 同一份目录连续两轮只重建一次() {
        when(rules.ruleCounter()).thenReturn(3);
        when(repository.jobCounter()).thenReturn(5);
        AtomicInteger builds = new AtomicInteger();
        Supplier<ContextFacts> builder = () -> {
            builds.incrementAndGet();
            return ContextFacts.EMPTY;
        };
        FactsCache cache = new FactsCache(rules, repository, () -> 0L, 30_000);

        cache.get(cache.revision(), builder);
        cache.get(cache.revision(), builder);

        assertThat(builds).hasValue(1);
    }

    @Test
    void 计数变了_下一次立刻重建() {
        AtomicInteger builds = new AtomicInteger();
        Supplier<ContextFacts> builder = () -> {
            builds.incrementAndGet();
            return ContextFacts.EMPTY;
        };
        FactsCache cache = new FactsCache(rules, repository, () -> 0L, 30_000);

        when(rules.ruleCounter()).thenReturn(3);
        when(repository.jobCounter()).thenReturn(5);
        cache.get(cache.revision(), builder);

        // 跑了一次对账：任务行数 +1
        when(repository.jobCounter()).thenReturn(6);
        cache.get(cache.revision(), builder);

        assertThat(builds).hasValue(2);
    }

    @Test
    void 过期了_即使计数没变也重建() {
        when(rules.ruleCounter()).thenReturn(3);
        when(repository.jobCounter()).thenReturn(5);
        AtomicLong now = new AtomicLong(0);
        AtomicInteger builds = new AtomicInteger();
        Supplier<ContextFacts> builder = () -> {
            builds.incrementAndGet();
            return ContextFacts.EMPTY;
        };
        FactsCache cache = new FactsCache(rules, repository, now::get, 30_000);

        cache.get(cache.revision(), builder);
        now.set(29_999);
        cache.get(cache.revision(), builder);
        assertThat(builds).as("还没到期，不该重建").hasValue(1);

        now.set(30_000);
        cache.get(cache.revision(), builder);
        assertThat(builds).as("到期后就该重建").hasValue(2);
    }

    @Test
    void 手工失效后立即重建() {
        when(rules.ruleCounter()).thenReturn(3);
        when(repository.jobCounter()).thenReturn(5);
        AtomicInteger builds = new AtomicInteger();
        Supplier<ContextFacts> builder = () -> {
            builds.incrementAndGet();
            return ContextFacts.EMPTY;
        };
        FactsCache cache = new FactsCache(rules, repository, () -> 0L, 30_000);

        cache.get(cache.revision(), builder);
        cache.invalidate();
        cache.get(cache.revision(), builder);

        assertThat(builds).hasValue(2);
    }
}
