package io.github.dolow.reconlens.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 中文操作人头的编解码契约。
 *
 * <p>浏览器里 {@code fetch(headers: {'X-Operator': '张三'})} 会**直接抛异常**——HTTP 头只能是
 * ISO-8859-1 字节。所以前端编码、后端解码，两半必须一起测：只测一半，另一半坏了照样发不出去。
 */
class OperatorHeaderFilterTest {

    private final OperatorHeaderFilter filter = new OperatorHeaderFilter();

    private HttpServletRequest through(String rawHeader) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/chat");
        if (rawHeader != null) {
            request.addHeader("X-Operator", rawHeader);
        }
        AtomicReference<HttpServletRequest> seen = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> seen.set((HttpServletRequest) req));
        return seen.get();
    }

    @Test
    @DisplayName("百分号编码的中文解回原文")
    void 中文解回() throws Exception {
        // %E5%BC%A0%E4%B8%89 = 张三
        HttpServletRequest request = through("%E5%BC%A0%E4%B8%89");

        assertThat(request.getHeader("X-Operator")).isEqualTo("张三");
        assertThat(request.getHeaders("X-Operator").nextElement()).isEqualTo("张三");
    }

    @Test
    @DisplayName("纯 ASCII 原样透传，老调用方行为不变")
    void 英文原样() throws Exception {
        assertThat(through("alice").getHeader("X-Operator")).isEqualTo("alice");
    }

    @Test
    @DisplayName("没带这个头就不碰")
    void 没有头也不出错() throws Exception {
        assertThat(through(null).getHeader("X-Operator")).isNull();
    }

    @Test
    @DisplayName("编码坏了按原样用，不让一个残缺名字把请求变成 500")
    void 坏编码不炸() throws Exception {
        assertThat(through("%E5%BC%A").getHeader("X-Operator")).isEqualTo("%E5%BC%A");
    }
}
