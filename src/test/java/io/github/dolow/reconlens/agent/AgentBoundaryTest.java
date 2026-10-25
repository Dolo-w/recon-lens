package io.github.dolow.reconlens.agent;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 包边界断言（设计文档 A7 的后半条）：{@code agent} 与引擎之间只能是单向依赖。
 *
 * <p>为什么用测试而不是注释：这条依赖方向是"Agent 可插拔"的唯一保证。
 * 一旦引擎的某个类 import 了 agent，{@code recon.agent.enabled=false} 就不再等于"回到引入之前"，
 * 而且这种 import 往往是为了图方便（比如在 {@code RuleCatalog} 里直接 new 一个 agent 类），
 * 代码评审很容易放过去 —— 编译器不会管，ArchUnit 会。
 */
@AnalyzeClasses(packages = "io.github.dolow.reconlens", importOptions = ImportOption.DoNotIncludeTests.class)
class AgentBoundaryTest {

    /**
     * 唯一的桥：{@code mcp.AgentMcpTools} 把 Agent 的试算能力注册成 MCP 工具。
     *
     * <p>这条规则原来写的是"包外不许碰 agent"，被这个类打破了 24 次。修法不是删规则，
     * 而是把边界挪到它真正该在的地方：<b>真正的风险不是"类被引用"，而是"Bean 被装配"</b> ——
     * agent 的类永远在 classpath 上（同一个 jar），关掉开关时它们只是不被实例化。
     * 只要这个桥自己带 {@code @ConditionalOnProperty}，且 {@code McpServerRunner} 用 {@code List} 注入
     * （空集合天然安全），"关掉 Agent = 回到引入之前"就仍然成立。
     *
     * <p>这个例外有代价：它让"包边界"从绝对约束降级成"默认约束 + 一处特批"。
     * 所以下面两条断言把它钉死 —— 特批的类必须真的是条件装配的，
     * 否则"关掉开关工具还是 11 个"这件事没有任何测试会失败。
     */
    @ArchTest
    static final ArchRule 引擎不得反向依赖_agent = noClasses()
            .that().resideOutsideOfPackage("io.github.dolow.reconlens.agent..")
            .and().doNotHaveFullyQualifiedName("io.github.dolow.reconlens.mcp.AgentMcpTools")
            .should().dependOnClassesThat().resideInAPackage("io.github.dolow.reconlens.agent..")
            .because("agent 是可选子系统：引擎（含 dsl / engine / rule / store / web / mcp）碰它一下，"
                    + "recon.agent.enabled=false 就不再等于\"回到引入 Agent 之前\"。"
                    + "唯一的例外是 mcp.AgentMcpTools —— 它是 Agent 能力的 MCP 注册桥，"
                    + "条件装配 + List 注入保证了 Bean 层面的隔离");

    @ArchTest
    static final ArchRule agent_不得依赖_web_与_mcp = noClasses()
            .that().resideInAPackage("io.github.dolow.reconlens.agent..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "io.github.dolow.reconlens.web..", "io.github.dolow.reconlens.mcp..")
            .because("依赖方向是 agent → dsl/engine/rule/store/explain：agent 不能反向调 Controller，"
                    + "也不能借 MCP 工具给自己开无人参与的发布通道"
                    + "（审批经 mcp.recon_submit_approval 代办，但决策必须来自人的明确表态）");

    /**
     * agent 包仍然不许碰审批服务。2026-10-17 边界演进：审批允许经 <b>mcp 包的代办工具</b>
     * （recon_submit_approval）提交，但那是"人的明确表态 + agent 执行器"的模型 ——
     * 决策上下文（confirmedBy）进审批审计。这条规则保证：能把生命周期推到 ACTIVE 的能力
     * 只存在于 mcp 桥（条件装配，可随 recon.agent.enabled 关闭）与 REST 两处，
     * agent 内核（起草/试算/诊断）依然没有任何发布权。
     */
    @ArchTest
    static final ArchRule agent_不得依赖审批服务 = noClasses()
            .that().resideInAPackage("io.github.dolow.reconlens.agent..")
            .should().dependOnClassesThat()
            .areAssignableTo(io.github.dolow.reconlens.rule.RuleLifecycleService.class)
            .because("审批的决策必须来自人的明确表态：agent 内核（起草/试算）连审批服务都不能引用，"
                    + "代办通道收敛在 mcp 包的 recon_submit_approval（条件装配）——"
                    + "agent 核心永远拿不到无人参与的发布通道");

    /**
     * 特批的代价要有人兜：那唯一的桥必须是<b>条件装配</b>的。
     *
     * <p>它一旦变成无条件的 {@code @Component}，{@code recon.agent.enabled=false} 时
     * {@code recon_draft_rule} 仍会出现在工具列表里，调用时才发现没有 orchestrator ——
     * 而"工具数量"这条断言在 {@code McpToolCatalogTest} 里用的是手工装配的 runner，抓不到这个。
     */
    @ArchTest
    static final ArchRule MCP注册桥必须是条件装配的 = classes()
            .that().haveFullyQualifiedName("io.github.dolow.reconlens.mcp.AgentMcpTools")
            .should().beAnnotatedWith(
                    org.springframework.boot.autoconfigure.condition.ConditionalOnProperty.class)
            .because("它是唯一允许反向依赖 agent 的类；没有条件装配，开关就失去了意义");

    /**
     * MCP 桥只能依赖"带确认码的审批转发"窄接口，不许直接引用审批服务本体。
     *
     * <p>为什么这条必要：{@code RuleLifecycleService} 上还有 {@code submit/approve/reject/skipTrial}
     * 这些<b>不需要确认码</b>的入口。2026-10-18 实测过模型用一句自拟的"用户已同意"在 47 ms 内
     * 把规则批成 ACTIVE —— 拦不住的原因之一就是桥拿到的是整个服务对象，随便挑一个方法调。
     * 现在它拿到的是 {@code ApprovalRelay}：只有 {@code confirmApproval}，实现里必须先核销确认码。
     */
    @ArchTest
    static final ArchRule mcp_不得直接依赖审批服务 = noClasses()
            .that().resideInAPackage("io.github.dolow.reconlens.mcp..")
            .should().dependOnClassesThat()
            .areAssignableTo(io.github.dolow.reconlens.rule.RuleLifecycleService.class)
            .because("MCP 桥只该拿到 ApprovalRelay（带确认码的窄接口）；直接引用 RuleLifecycleService "
                    + "等于把不校验确认码的 submit/approve 也递给了模型");

    /**
     * ADR-0001 N2：<b>任何 runtime 都不得直接持有审批服务</b>，会话 runtime 也算。
     *
     * <p>这条不是形式主义。{@code ChatRuntime} 是被"一句话"驱动的——模型/路由解析出什么就调什么。
     * 只要它能拿到 {@code RuleLifecycleService}，用户在会话里说一句"那就批了吧"，
     * 实现者顺手写一行 {@code lifecycle.approve(...)} 就能把规则推成 ACTIVE，
     * 而审批链要求的 {@code confirmationCode} + 内容哈希会被完全绕开。
     * 现在 chat 包里一个引用都没有；这条规则保证它<b>将来也不会有</b>，
     * 而不是靠"这次我记得"。
     */
    @ArchTest
    static final ArchRule 会话不得直接依赖审批服务 = noClasses()
            .that().resideInAPackage("io.github.dolow.reconlens.chat..")
            .should().dependOnClassesThat()
            .areAssignableTo(io.github.dolow.reconlens.rule.RuleLifecycleService.class)
            .because("会话是受自然语言驱动的 runtime：能拿到审批服务就能绕过确认码直接发布。"
                    + "审批只能经 ReconMcpTools.recon_submit_approval → ApprovalRelay（核销确认码）");

    /**
     * ADR-0001 N8 的编译期那一半：会话仓储只许 {@code chat} 包直接用。
     *
     * <p>仓储的方法签名里虽然有 {@code SessionScope}（运行时收窄），但**签名能被绕过**：
     * 谁都可以 {@code new SessionScope("alice")} 造一个别人的 scope。真正的约束是
     * "只有一处代码负责构造 scope" —— 也就是 web 层必须经 {@code ChatSessionService}
     * （它从请求头取 operator），而不是自己 new 一个 scope 去查库。
     */
    @ArchTest
    static final ArchRule 会话仓储只许会话包直接用 = noClasses()
            .that().resideOutsideOfPackages(
                    "io.github.dolow.reconlens.chat..", "io.github.dolow.reconlens.store..")
            .should().dependOnClassesThat()
            .haveFullyQualifiedName("io.github.dolow.reconlens.store.ChatSessionRepository")
            .because("收窄条件必须只有一处：web 层自己查仓储意味着它自己构造 SessionScope，"
                    + "而 operator 来自请求头——绕过校验只差一行 new");
}
