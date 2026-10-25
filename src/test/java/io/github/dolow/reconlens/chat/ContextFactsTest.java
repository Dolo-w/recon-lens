package io.github.dolow.reconlens.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.dolow.reconlens.rule.RuleCatalog;
import io.github.dolow.reconlens.store.ReconRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 事实卡片的边界测试。
 *
 * <p>要守的不是"格式好看"，而是两条：
 * <b>取不到事实时不能充当"库里没有"</b>（否则一次数据库抖动会把所有规则判成不存在），
 * 以及卡片里**只有元数据**（它绝不能变成第二条读数据行的路径）。
 */
class ContextFactsTest {

    @Test
    @DisplayName("库是空的：没有规则、没有任务，但仍然给出一张可用的卡片")
    void 空库也能出卡片() {
        RuleCatalog catalog = mock(RuleCatalog.class);
        ReconRepository repository = mock(ReconRepository.class);
        when(catalog.all()).thenReturn(java.util.List.of());
        when(repository.listJobs(anyInt())).thenReturn(java.util.List.of());

        ContextFacts facts = ContextFacts.build(catalog, repository);

        assertThat(facts.knowsRules()).isFalse();
        assertThat(facts.knowsJobs()).isFalse();
        assertThat(facts.text()).contains("没有数据行的访问权限");
    }

    @Test
    @DisplayName("取事实失败：退化成 EMPTY，调用方据此跳过校验")
    void 取事实失败退化成空() {
        RuleCatalog catalog = mock(RuleCatalog.class);
        ReconRepository repository = mock(ReconRepository.class);
        when(catalog.all()).thenThrow(new IllegalStateException("db down"));
        when(catalog.allResolved()).thenThrow(new IllegalStateException("db down"));
        when(catalog.latestAnyState()).thenThrow(new IllegalStateException("db down"));

        ContextFacts facts = ContextFacts.build(catalog, repository);

        assertThat(facts).isSameAs(ContextFacts.EMPTY);
        assertThat(facts.knowsRules()).isFalse();
    }

    @Test
    @DisplayName("空集合不代表「库里没有」：knowsX 用来区分「没取到」与「真没有」")
    void 空集合不等于没有() {
        ContextFacts empty = ContextFacts.EMPTY;
        assertThat(empty.knowsRules()).isFalse();
        assertThat(empty.ruleCodes()).isEmpty();

        ContextFacts some = new ContextFacts("t", java.util.Set.of("A"), java.util.Set.of(1L),
                java.util.Set.of("bill_left"));
        assertThat(some.knowsRules()).isTrue();
        assertThat(some.ruleCodes()).containsExactly("A");
    }
}
