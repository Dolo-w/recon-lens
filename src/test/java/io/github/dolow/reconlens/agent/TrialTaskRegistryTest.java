package io.github.dolow.reconlens.agent;

import io.github.dolow.reconlens.rule.RuleTrialRunner;
import io.github.dolow.reconlens.store.AgentRunRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 试算队列的两条工程性质：同一版草稿不重复排队、进程重启后遗留会话不留假 RUNNING。
 *
 * <p>这两条都不涉及试算本身（那是 {@code ShadowTrialService} 的事），只测调度器的簿记。
 */
class TrialTaskRegistryTest {

    private final AgentRunRepository runs = mock(AgentRunRepository.class);
    private final AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
    private final RuleTrialRunner runner = mock(RuleTrialRunner.class);
    private final TrialTaskRegistry registry = new TrialTaskRegistry(runs, orchestrator, runner, 1, 8);

    @AfterEach
    void shutdown() {
        registry.shutdown();
    }

    @Test
    @DisplayName("同一版草稿重复提交：复用第一次的会话号，不再排第二次队")
    void submittingTheSameDraftTwiceReusesTheRunId() {
        when(runs.startRun(anyString(), anyString(), anyString())).thenReturn(41L);
        // 让 worker 阻塞在 run 上，保证第二次提交时第一次仍在"进行中"
        when(runner.run(anyString(), anyInt())).thenAnswer(invocation -> {
            Thread.sleep(300);
            return new RuleTrialRunner.Result(invocation.getArgument(1),
                    new io.github.dolow.reconlens.domain.TrialState.Trialed(
                            io.github.dolow.reconlens.domain.TrialVerdict.PASS,
                            io.github.dolow.reconlens.domain.Coverage.FULL,
                            java.util.List.of()));
        });

        long first = registry.submitTrial("ORDER_RECON", 3, "wang");
        long second = registry.submitTrial("ORDER_RECON", 3, "wang");

        assertThat(second).isEqualTo(first);
        // startRun 只该被调一次 —— 第二次直接复用了在跑的会话
        verify(runs, org.mockito.Mockito.times(1)).startRun(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("不同版本各自排队，不互相复用")
    void differentVersionsGetTheirOwnRun() {
        when(runs.startRun(anyString(), anyString(), anyString())).thenReturn(1L, 2L);
        when(runner.run(anyString(), anyInt())).thenAnswer(invocation -> {
            Thread.sleep(300);
            return new RuleTrialRunner.Result(invocation.getArgument(1),
                    new io.github.dolow.reconlens.domain.TrialState.Trialed(
                            io.github.dolow.reconlens.domain.TrialVerdict.PASS,
                            io.github.dolow.reconlens.domain.Coverage.FULL,
                            java.util.List.of()));
        });

        long v3 = registry.submitTrial("ORDER_RECON", 3, "wang");
        long v4 = registry.submitTrial("ORDER_RECON", 4, "wang");

        assertThat(v3).isNotEqualTo(v4);
    }

    @Test
    @DisplayName("启动时只清点遗留的 RUNNING 会话，不再把它们标成失败")
    void startupCountsLeftoverRunningRunsWithoutFailingThem() {
        when(runs.unfinishedRunIds()).thenReturn(java.util.List.of(7L, 8L));

        registry.run(null);

        // 遗留会话现在交给 AgentRunRecovery（从最后一个产物接着做），这里只清点、不改状态：
        // 库层面已经没有"把残留会话标成 FAILED"的入口了，所以这个测试能断言的就是"只读了一次"
        verify(runs).unfinishedRunIds();
    }

    @Test
    @DisplayName("清点失败不阻止启动")
    void countFailureDoesNotStopTheApplication() {
        when(runs.unfinishedRunIds()).thenThrow(new IllegalStateException("库连不上"));

        registry.run(null);

        // 不抛异常就算通过；这里再确认一次它确实尝试过
        verify(runs, timeout(TimeUnit.SECONDS.toMillis(1)).times(1)).unfinishedRunIds();
        verify(orchestrator, never()).run(any(), anyString());
    }
}


