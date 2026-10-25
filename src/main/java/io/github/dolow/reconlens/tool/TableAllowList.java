package io.github.dolow.reconlens.tool;

/**
 * 表是否可见的只读判据。
 *
 * <p>为什么要有这个接口：白名单的真实实现是 {@code agent.SchemaCatalog}（配置 {@code
 * recon.agent.schema.tables} ∪ 对话内注册表），但 ArchUnit 有一条硬规则——除
 * {@code mcp.AgentMcpTools} 外，任何包都不得依赖 {@code agent..}，为的是
 * {@code recon.agent.enabled=false} 时能完整回到"引入 Agent 之前"。
 *
 * <p>所以把"判断"抽成一个接口放在 {@code tool} 包，由 {@code agent} 包实现并注入。
 * 依赖方向变成 {@code agent → tool}，这是允许的方向（{@code agent} 可以依赖别人，
 * 别人不能依赖 {@code agent}）。
 *
 * <p><b>默认必须是拒绝</b>：拿不到实现（没开 Agent 模式）时，调用方要按"全都不可见"处理，
 * 而不是"什么都让看"。
 */
public interface TableAllowList {

    /** 这张表是否在闸门 1 的白名单里。实现方不必保证表真的存在。 */
    boolean allows(String table);
}
