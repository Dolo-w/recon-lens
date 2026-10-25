package io.github.dolow.reconlens.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 脱敏的两条防线：按值形态、按列名。
 *
 * <p>这里刻意用"看得出来对不对"的断言（打码后还能不能读出城市/姓/末四位），
 * 而不是只断言"和原文不一样"—— 后者在返回 `***` 时也会通过，但那样的脱敏没有信息量。
 */
class MaskingTest {

    @Test
    @DisplayName("手机号保留前3后4")
    void 手机() {
        assertThat(Masking.mask("phone", "13812345678")).isEqualTo("138****5678");
        assertThat(Masking.mask("ext_01", "13812345678")).isEqualTo("138****5678");
    }

    @Test
    @DisplayName("邮箱保留首字母与域名")
    void 邮箱() {
        assertThat(Masking.mask("email", "zhangsan@example.com")).isEqualTo("z***@example.com");
    }

    @Test
    @DisplayName("身份证与银行卡都只留末4位")
    void 证件与卡号() {
        assertThat(Masking.mask("id_card", "110101199003071234")).isEqualTo("****1234");
        assertThat(Masking.mask("card_no", "6222021234567890")).isEqualTo("****7890");
    }

    @Test
    @DisplayName("姓名只留姓_地址只留到市")
    void 姓名与地址() {
        assertThat(Masking.mask("name", "张三丰")).isEqualTo("张**");
        assertThat(Masking.mask("address", "北京市朝阳区建国路88号")).isEqualTo("北京市朝阳区……");
    }

    @Test
    @DisplayName("值形态比列名可靠_ext_01 里的手机号也要打码")
    void 值形态优先() {
        // 列名完全不敏感，但值是手机号
        assertThat(Masking.mask("remark", "13900001111")).isEqualTo("139****1111");
    }

    @Test
    @DisplayName("普通值不打码_原样返回")
    void 普通值() {
        assertThat(Masking.mask("order_no", "ORD20260001")).isEqualTo("ORD20260001");
        assertThat(Masking.mask("amount", "128.50")).isEqualTo("128.50");
        assertThat(Masking.mask(null, null)).isNull();
        assertThat(Masking.mask("status", "")).isEmpty();
    }

    @Test
    @DisplayName("敏感列但形态不认识_一律只留末4位")
    void 敏感列兜底() {
        // 列名像账号，值却是奇形怪状的：宁可少给信息
        assertThat(Masking.mask("account_no", "ABCD-EFGH-1234")).isEqualTo("****1234");
    }
}
