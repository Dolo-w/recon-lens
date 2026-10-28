package io.github.dolow.reconlens.agent;

import io.github.dolow.reconlens.config.ReconProperties;
import io.github.dolow.reconlens.dsl.ShardSplitAnalyzer;
import io.github.dolow.reconlens.engine.ReconEngine;
import io.github.dolow.reconlens.explain.ExplainService;
import io.github.dolow.reconlens.llm.LlmChatClient;
import io.github.dolow.reconlens.rule.RuleCatalog;
import io.github.dolow.reconlens.store.AgentRunRepository;
import io.github.dolow.reconlens.store.GateHealthRepository;
import io.github.dolow.reconlens.store.ReconRepository;
import io.github.dolow.reconlens.store.RegisteredTableRepository;
import io.github.dolow.reconlens.store.RuleRepository;
import io.github.dolow.reconlens.store.TrialSampleRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import io.github.dolow.reconlens.tool.TableAllowList;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;
import java.time.Clock;

/**
 * Agent 子系统的装配入口。整个包只有这一处对 Spring 可见的装配点，
 * 且被 {@code recon.agent.enabled} 整体开关控制。
 *
 * <p>为什么 agent 是"同进程的一个包"而不是独立服务：闸门 2 的试算需要<b>结构化对象</b>
 * （差异集合、样本规模、覆盖等级），而跨进程只能拿到工具返回的自然语言文本。
 * 但同进程不等于可以随便互相调用 —— 依赖方向 {@code agent → dsl/engine} 是单向的，
 * 由 {@code AgentBoundaryTest} 的 ArchUnit 规则断言。
 *
 * <p>目前只有 {@link ShardSplitAnalyzer} 一个常驻 Bean：它是闸门 1 的第一项检查（分片口径同源）的
 * 判定核心，也是本包的"装配探针" —— 开关打开它就在，关掉它一个都没有。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "recon.agent", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(AgentProperties.class)
// 调度器随整个包一起开关：关掉 Agent 时连 @Scheduled 的扫描线程都不该存在
@EnableScheduling
public class AgentConfiguration {

    @Bean
    public ShardSplitAnalyzer shardSplitAnalyzer() {
        return new ShardSplitAnalyzer();
    }

    /** 闸门 1 的对象白名单（fail-closed：没配白名单 = 一张表也不放行）。白名单 = 配置 ∪ 动态注册。 */
    @Bean
    public RegisteredTableRepository registeredTableRepository(JdbcTemplate jdbcTemplate) {
        return new RegisteredTableRepository(jdbcTemplate);
    }

    @Bean
    public SchemaCatalog schemaCatalog(DataSource dataSource, AgentProperties properties,
                                       RegisteredTableRepository registeredTableRepository) {
        return new SchemaCatalog(dataSource, properties, registeredTableRepository);
    }

    /**
     * 白名单的只读视图，给 {@code tool} / {@code chat} 用。
     *
     * <p>为什么不让它们直接注入 {@code SchemaCatalog}：本包属于 {@code agent..}，
     * ArchUnit 禁止其它包依赖它（保证 {@code recon.agent.enabled=false} 能完整退场）。
     */
    @Bean
    public TableAllowList tableAllowList(SchemaCatalog schemaCatalog) {
        return new SchemaTableAllowList(schemaCatalog);
    }

    /** 动态表注册（两段式 preview → confirm）：新用户接入不改配置、不重启。 */
    @Bean
    public TableRegistrar tableRegistrar(SchemaCatalog schemaCatalog,
                                         RegisteredTableRepository registeredTableRepository,
                                         JdbcTemplate jdbcTemplate) {
        return new TableRegistrar(schemaCatalog, registeredTableRepository, jdbcTemplate);
    }

    /** 文件导入（CSV/Excel → 建表灌数 → 复用 TableRegistrar 注册进白名单）。 */
    @Bean
    public FileImporter fileImporter(AgentProperties agentProperties,
                                     TableRegistrar tableRegistrar,
                                     JdbcTemplate jdbcTemplate) {
        return new FileImporter(agentProperties, tableRegistrar, jdbcTemplate);
    }

    /** 闸门 1 本体。返回违规列表而不是抛异常：LLM 自纠正要一次拿到全部问题。 */
    @Bean
    public RuleSafetyValidator ruleSafetyValidator(SchemaCatalog schemaCatalog,
                                                   ShardSplitAnalyzer shardSplitAnalyzer,
                                                   AgentProperties properties) {
        return new RuleSafetyValidator(schemaCatalog, shardSplitAnalyzer,
                properties.getSafety().getToleranceMultiplier(),
                properties.getSafety().getMaxTolerancePercent(),
                properties.getSafety().getMaxShardCount(),
                properties.getSafety().isForceDraftViolation());
    }

    // ---------------- 闸门 2：影子试算 ----------------
    //
    // 下面这些类<b>不带</b> @Component / @Service：它们的装配必须和整个包一起被开关控制。
    // 加了注解就绕过了 @ConditionalOnProperty，"关掉时一个 Bean 都不装配"那条断言会立刻失效 ——
    // 而那正是"引入 Agent 是一次可回滚的配置变更"这句话的全部依据。

    @Bean
    public SampleWindowProbe sampleWindowProbe(JdbcTemplate jdbcTemplate) {
        return new SampleWindowProbe(jdbcTemplate);
    }

    /** 样本表的租约登记。owner 只用于排障，判活靠 heartbeat_at。 */
    @Bean
    public TrialSampleRepository trialSampleRepository(JdbcTemplate jdbcTemplate) {
        return new TrialSampleRepository(jdbcTemplate, TrialSampleRepository.defaultOwner());
    }

    @Bean
    public SampleMaterializer sampleMaterializer(JdbcTemplate jdbcTemplate, TrialSampleRepository leases) {
        return new SampleMaterializer(jdbcTemplate, leases);
    }

    @Bean
    public CanaryInjector canaryInjector(JdbcTemplate jdbcTemplate) {
        return new CanaryInjector(jdbcTemplate);
    }

    /** 判定阈值来自 {@code recon.trial.*}，并且它们参与 GateVersion —— 改阈值等于换一套闸门。 */
    @Bean
    public TrialVerdictResolver trialVerdictResolver(ReconProperties properties) {
        return new TrialVerdictResolver(properties.getTrial());
    }

    @Bean
    public ShadowTrialService shadowTrialService(RuleRepository ruleRepository,
                                                 ReconRepository reconRepository,
                                                 RuleCatalog ruleCatalog,
                                                 ReconEngine reconEngine,
                                                 SampleWindowProbe sampleWindowProbe,
                                                 SampleMaterializer sampleMaterializer,
                                                 CanaryInjector canaryInjector,
                                                 GateHealthRepository gateHealthRepository,
                                                 RuleSafetyValidator ruleSafetyValidator,
                                                 TrialVerdictResolver trialVerdictResolver,
                                                 ReconProperties properties,
                                                 ObjectMapper objectMapper) {
        return new ShadowTrialService(ruleRepository, reconRepository, ruleCatalog, reconEngine,
                sampleWindowProbe, sampleMaterializer, canaryInjector, gateHealthRepository,
                ruleSafetyValidator, trialVerdictResolver, properties, objectMapper);
    }

    /**
     * 试算触发口的桥：rule 包的 {@code RuleTrialRunner} 接口 + agent 实现。
     * mcp 包只认接口 —— ArchUnit 禁止 mcp 直依赖 agent，依赖倒置后边界与能力两全。
     */
    @Bean
    public io.github.dolow.reconlens.rule.RuleTrialRunner ruleTrialRunner(ShadowTrialService shadowTrialService) {
        return (code, version) -> {
            ShadowTrialService.TrialResult result = shadowTrialService.trial(code, version);
            return new io.github.dolow.reconlens.rule.RuleTrialRunner.Result(result.version(), result.state());
        };
    }

    /** 残留回收。类上没有 @Scheduled 之外的注解，装配同样受整个包的开关控制。 */
    @Bean
    public TrialCleanupScheduler trialCleanupScheduler(ShadowTrialService shadowTrialService,
                                                       SampleMaterializer sampleMaterializer,
                                                       ReconProperties properties) {
        return new TrialCleanupScheduler(shadowTrialService, sampleMaterializer, properties);
    }

    // ---------------- M3：会话轨迹 / 起草 / 编排 ----------------

    /**
     * 会话与步骤轨迹。同 {@code TrialSampleRepository}：不带 @Repository，
     * 否则它会在 {@code recon.agent.enabled=false} 时依然被装配。
     */
    @Bean
    public AgentRunRepository agentRunRepository(JdbcTemplate jdbcTemplate) {
        return new AgentRunRepository(jdbcTemplate);
    }

    /**
     * 规则起草。它<b>只依赖 LlmChatClient 的可用性</b>，不关心是哪家模型 ——
     * 因此 {@code recon.llm.enabled=false} 时它依然被装配，只是每次都返回"转人工"。
     * 这个设计让"没有 LLM 的环境"和"LLM 挂了的环境"走同一条代码路径。
     */
    @Bean
    public RuleDrafter ruleDrafter(LlmChatClient llmChatClient, SchemaCatalog schemaCatalog,
                                   RuleCatalog ruleCatalog, RuleSafetyValidator ruleSafetyValidator,
                                   ObjectMapper objectMapper) {
        return new RuleDrafter(llmChatClient, schemaCatalog, ruleCatalog, ruleSafetyValidator, objectMapper);
    }

    @Bean
    public AgentOrchestrator agentOrchestrator(AgentRunRepository agentRunRepository,
                                               RuleCatalog ruleCatalog,
                                               ReconEngine reconEngine,
                                               ShadowTrialService shadowTrialService,
                                               RuleDrafter ruleDrafter,
                                               ExplainService explainService,
                                               AgentProperties agentProperties,
                                               ObjectMapper objectMapper,
                                               io.github.dolow.reconlens.store.RuleRepository ruleRepository) {
        return new AgentOrchestrator(agentRunRepository, ruleCatalog, reconEngine, shadowTrialService,
                ruleDrafter, explainService, agentProperties, objectMapper, ruleRepository);
    }

    /**
     * 把恢复能力装成引擎侧的窄接口，供 web 的 {@code POST /api/agent/runs/{id}/resume} 使用。
     *
     * <p>为什么不直接让控制器注入 {@code AgentOrchestrator}：{@code AgentBoundaryTest} 断言
     * 「agent 之外只有 mcp.AgentMcpTools 可以依赖 agent」，web 碰一下那条规则就破了。
     */
    @Bean
    public io.github.dolow.reconlens.rule.RunResumer runResumer(AgentOrchestrator orchestrator) {
        return (runId, operator) -> {
            AgentOrchestrator.AgentRunResult result = orchestrator.resume(runId, operator);
            return new io.github.dolow.reconlens.rule.RunResumer.Outcome(
                    result.status().name(), result.ruleCode(), result.draftVersion(), result.jobId(), result.message());
        };
    }

    /**
     * 后台试算登记处。<b>destroyMethod 必须显式写</b>：它持有线程池，
     * 不关的话每次上下文刷新都会留一组 daemon 线程（测试里尤其明显）。
     */
    @Bean(destroyMethod = "shutdown")
    public TrialTaskRegistry trialTaskRegistry(AgentRunRepository agentRunRepository,
                                               AgentOrchestrator agentOrchestrator,
                                               io.github.dolow.reconlens.rule.RuleTrialRunner ruleTrialRunner,
                                               AgentProperties agentProperties) {
        return new TrialTaskRegistry(agentRunRepository, agentOrchestrator, ruleTrialRunner,
                agentProperties.getTrialThreads(), agentProperties.getTrialQueueCapacity(),
                agentProperties.getStaleRunMinutes());
    }

    /**
     * 试算排队口：{@code recon_define_rule} 手写规则时走它，与 Agent 起草共用同一条队列。
     *
     * <p>它只在 Agent 装配存在时才有实现 —— 没有实现时 {@code ReconMcpTools} 退回同步试算
     * （{@code ObjectProvider.getIfAvailable()}），所以关掉 Agent 依然能定义规则，只是会阻塞十几秒。
     */
    @Bean
    public io.github.dolow.reconlens.rule.RuleTrialScheduler ruleTrialScheduler(TrialTaskRegistry registry) {
        return registry::submitTrial;
    }

    /**
     * 文件导入的后台任务登记处（{@code recon_import_file(async=true)}）。
     *
     * <p>与试算队列分开是有意的：导入会长时间占一条连接做批量写，和试算挤同一条队列会互相饿死。
     */
    @Bean(destroyMethod = "shutdown")
    public ImportTaskRegistry importTaskRegistry(FileImporter fileImporter) {
        return new ImportTaskRegistry(fileImporter);
    }

    // ---------------- M5：巡检 ----------------

    /**
     * 巡检本体。<b>只读业务数据</b>，唯一的写入是给自己留轨迹（见 {@code PerceptionService} 的类注释）。
     *
     * <p>{@link java.time.Clock} 从参数进而不是 {@code Instant.now()}：巡检的判据里含"最近 N 次"
     * 与"抑制窗口分钟数"，测试要能把时间钉死，否则只能靠 sleep 等 —— 那是最慢也最不稳的测试写法。
     */
    @Bean
    public PerceptionService perceptionService(ReconRepository reconRepository,
                                               AgentRunRepository agentRunRepository,
                                               AgentProperties agentProperties,
                                               ObjectMapper objectMapper) {
        return new PerceptionService(reconRepository, agentRunRepository, agentProperties,
                objectMapper, Clock.systemUTC());
    }

    /**
     * 巡检定时器。{@code start()} 在装配时调用：首轮也要等一个完整间隔，
     * 所以不存在"上下文还没就绪就跑去查库"的窗口。
     */
    @Bean(destroyMethod = "shutdown")
    public PerceptionScheduler perceptionScheduler(PerceptionService perceptionService,
                                                   AgentProperties agentProperties) {
        PerceptionScheduler scheduler = new PerceptionScheduler(perceptionService, agentProperties);
        scheduler.start();
        return scheduler;
    }
}
