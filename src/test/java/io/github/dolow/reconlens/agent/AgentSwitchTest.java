package io.github.dolow.reconlens.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dolow.reconlens.config.ReconProperties;
import io.github.dolow.reconlens.dsl.ShardSplitAnalyzer;
import io.github.dolow.reconlens.engine.ReconEngine;
import io.github.dolow.reconlens.rule.RuleCatalog;
import io.github.dolow.reconlens.store.AgentRunRepository;
import io.github.dolow.reconlens.store.GateHealthRepository;
import io.github.dolow.reconlens.store.ReconRepository;
import io.github.dolow.reconlens.store.RuleRepository;
import io.github.dolow.reconlens.store.TrialSampleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 开关的验收（设计文档 A7 的前半条）：{@code recon.agent.enabled=false} 时 agent 包一个 Bean 都不装配。
 *
 * <p>这条比"能关掉"更具体：它断言的是<b>一个 Bean 都没有</b>。
 * 只要有任何一个 agent Bean 被无条件注册，"关掉 Agent 就等于回到引入之前"这句话就不成立，
 * 回滚也就不是一个配置项，而是一次发版。
 *
 * <p>所以闸门 2 的那几个类刻意<b>不带</b> {@code @Component} —— 注解会绕过
 * {@code @ConditionalOnProperty}，这个测试是唯一会发现"顺手加个注解"的地方。
 */
class AgentSwitchTest {

    private final DataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:agent_switch;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");

    private final JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

    /** 这里只验证装配，不连真库：下游全部用 mock 顶替。 */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(DataSource.class, () -> dataSource)
            .withBean(JdbcTemplate.class, () -> jdbcTemplate)
            .withBean(ReconProperties.class, ReconProperties::new)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(RuleRepository.class, () -> mock(RuleRepository.class))
            .withBean(ReconRepository.class, () -> mock(ReconRepository.class))
            .withBean(RuleCatalog.class, () -> mock(RuleCatalog.class))
            .withBean(ReconEngine.class, () -> mock(ReconEngine.class))
            .withBean(GateHealthRepository.class, () -> mock(GateHealthRepository.class))
            .withBean(io.github.dolow.reconlens.llm.LlmChatClient.class,
                    () -> mock(io.github.dolow.reconlens.llm.LlmChatClient.class))
            .withBean(io.github.dolow.reconlens.explain.ExplainService.class,
                    () -> mock(io.github.dolow.reconlens.explain.ExplainService.class))
            .withUserConfiguration(AgentConfiguration.class);

    @Test
    void loadsNothingWhenAgentIsDisabled() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(AgentProperties.class);
            assertThat(context).doesNotHaveBean(ShardSplitAnalyzer.class);
            assertThat(context).doesNotHaveBean(SchemaCatalog.class);
            assertThat(context).doesNotHaveBean(RuleSafetyValidator.class);
            assertThat(context).doesNotHaveBean(SampleWindowProbe.class);
            assertThat(context).doesNotHaveBean(TrialSampleRepository.class);
            assertThat(context).doesNotHaveBean(SampleMaterializer.class);
            assertThat(context).doesNotHaveBean(CanaryInjector.class);
            assertThat(context).doesNotHaveBean(TrialVerdictResolver.class);
            assertThat(context).doesNotHaveBean(TrialCleanupScheduler.class);
            assertThat(context).doesNotHaveBean(ShadowTrialService.class);
            // M3：轨迹 / 起草 / 编排 —— 关掉 Agent 时连"会话"这个概念都不该存在
            assertThat(context).doesNotHaveBean(AgentRunRepository.class);
            assertThat(context).doesNotHaveBean(RuleDrafter.class);
            assertThat(context).doesNotHaveBean(AgentOrchestrator.class);
            assertThat(context).doesNotHaveBean(TrialTaskRegistry.class);
            // M5：巡检 —— 关掉 Agent 就既没有巡检 Bean，也没有那条守护线程
            assertThat(context).doesNotHaveBean(PerceptionService.class);
            assertThat(context).doesNotHaveBean(PerceptionScheduler.class);
        });
    }

    @Test
    void loadsAgentBeansWhenEnabled() {
        runner.withPropertyValues("recon.agent.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AgentProperties.class);
            assertThat(context).hasSingleBean(ShardSplitAnalyzer.class);
            assertThat(context).hasSingleBean(SchemaCatalog.class);
            assertThat(context).hasSingleBean(RuleSafetyValidator.class);
            assertThat(context).hasSingleBean(SampleWindowProbe.class);
            assertThat(context).hasSingleBean(TrialSampleRepository.class);
            assertThat(context).hasSingleBean(SampleMaterializer.class);
            assertThat(context).hasSingleBean(CanaryInjector.class);
            assertThat(context).hasSingleBean(TrialVerdictResolver.class);
            assertThat(context).hasSingleBean(TrialCleanupScheduler.class);
            assertThat(context).hasSingleBean(ShadowTrialService.class);
            assertThat(context).hasSingleBean(AgentRunRepository.class);
            assertThat(context).hasSingleBean(RuleDrafter.class);
            assertThat(context).hasSingleBean(AgentOrchestrator.class);
            assertThat(context).hasSingleBean(TrialTaskRegistry.class);
            assertThat(context).hasSingleBean(PerceptionService.class);
            assertThat(context).hasSingleBean(PerceptionScheduler.class);
            assertThat(context.getBean(AgentProperties.class).isEnabled()).isTrue();
        });
    }

    @Test
    void treatsExplicitFalseAsDisabled() {
        runner.withPropertyValues("recon.agent.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(ShardSplitAnalyzer.class);
            assertThat(context).doesNotHaveBean(RuleSafetyValidator.class);
            assertThat(context).doesNotHaveBean(ShadowTrialService.class);
        });
    }
}
