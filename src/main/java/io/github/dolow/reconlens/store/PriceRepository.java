package io.github.dolow.reconlens.store;

import io.github.dolow.reconlens.domain.PriceEntry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/** token 价格表 SQL 层。纯配置数据（可删改），审计语义在 source_note 字段不在台账。 */
@Repository
public class PriceRepository {

    private static final String COLUMNS =
            "id, price_code, model, token_type, unit_price_per_million, currency, "
                    + "effective_from, effective_to, tier_min, tier_max, source_note, created_at";

    private static final RowMapper<PriceEntry> MAPPER = (rs, i) -> new PriceEntry(
            rs.getLong("id"),
            rs.getString("price_code"),
            rs.getString("model"),
            rs.getString("token_type"),
            rs.getBigDecimal("unit_price_per_million"),
            rs.getString("currency"),
            rs.getTimestamp("effective_from").toInstant(),
            ts(rs.getTimestamp("effective_to")),
            rs.getBigDecimal("tier_min"),
            rs.getBigDecimal("tier_max"),
            rs.getString("source_note"),
            ts(rs.getTimestamp("created_at")));

    private static java.time.Instant ts(java.sql.Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private final JdbcTemplate jdbc;

    public PriceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public PriceEntry insert(PriceEntry e) {
        jdbc.update("""
                INSERT INTO recon_price_entry (price_code, model, token_type, unit_price_per_million,
                        currency, effective_from, effective_to, tier_min, tier_max, source_note)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """,
                e.priceCode(), e.model(), e.tokenType(), e.unitPricePerMillion(),
                e.currency(), java.sql.Timestamp.from(e.effectiveFrom()),
                e.effectiveTo() == null ? null : java.sql.Timestamp.from(e.effectiveTo()),
                e.tierMin(), e.tierMax(), e.sourceNote());
        // 单写者，无并发竞态窗口：自然键回查刚插的那条
        return jdbc.queryForObject("SELECT " + COLUMNS + " FROM recon_price_entry WHERE price_code=? AND model=? "
                        + "AND token_type=? AND effective_from=? AND tier_min=?",
                MAPPER, e.priceCode(), e.model(), e.tokenType(),
                java.sql.Timestamp.from(e.effectiveFrom()), e.tierMin());
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM recon_price_entry WHERE id = ?", id);
    }

    public List<PriceEntry> list(String priceCode, String model, String tokenType) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM recon_price_entry WHERE 1=1");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (priceCode != null && !priceCode.isBlank()) {
            sql.append(" AND price_code = ?");
            args.add(priceCode.trim());
        }
        if (model != null && !model.isBlank()) {
            sql.append(" AND model = ?");
            args.add(model.trim().toLowerCase(java.util.Locale.ROOT));
        }
        if (tokenType != null && !tokenType.isBlank()) {
            sql.append(" AND token_type = ?");
            args.add(tokenType.trim().toUpperCase(java.util.Locale.ROOT));
        }
        sql.append(" ORDER BY price_code, model, token_type, effective_from, tier_min");
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    /** 解析候选：同 code + model + type 的全部条目（时间窗/阶梯筛选交给 resolver）。 */
    public List<PriceEntry> candidates(String priceCode, String model, String tokenType) {
        return jdbc.query("SELECT " + COLUMNS + " FROM recon_price_entry "
                        + "WHERE price_code = ? AND model = ? AND token_type = ? "
                        + "ORDER BY effective_from, tier_min",
                MAPPER, priceCode, model, tokenType);
    }
}
