package io.github.dolow.reconlens.agent;

import io.github.dolow.reconlens.tool.TableAllowList;

/**
 * 把闸门 1 的白名单（{@code SchemaCatalog}）适配成 {@link TableAllowList}。
 *
 * <p>这一层只有一句话，但它把"白名单怎么算"留在 agent 包里，而让 {@code tool} 包
 * 能问出"这张表允许看吗"——否则 {@code tool} 只能 import {@code agent..}，
 * 撞上 ArchUnit 的"引擎不得反向依赖 agent"，那条规定是
 * {@code recon.agent.enabled=false} 能完整退场的保证。
 */
public class SchemaTableAllowList implements TableAllowList {

    private final SchemaCatalog catalog;

    public SchemaTableAllowList(SchemaCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public boolean allows(String table) {
        return table != null && catalog.allows(table);
    }
}
