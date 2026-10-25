package io.github.dolow.reconlens.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 让 {@code X-Operator} 支持中文（以及其他非 ISO-8859-1 字符）。
 *
 * <p><b>为什么需要它</b>：HTTP 头按规范只能是 ISO-8859-1 字节。浏览器里写
 * {@code fetch(..., headers: {'X-Operator': '张三'})} 会直接抛
 * <i>String contains non ISO-8859-1 code point</i>，请求根本发不出去——不是后端拒了，是前端连构造都不行。
 * 而"操作人"在中文机构里就是中文名，这不是边界情况。
 *
 * <p><b>约定</b>：客户端用 {@code encodeURIComponent} 编码后放进头，服务端在这里解回 UTF-8。
 * 只改一个位置，所有 {@code @RequestHeader("X-Operator")} 的 Controller 都不用动；
 * 没编码过的值（纯 ASCII）原样透传，老调用方行为不变。
 *
 * <p><b>为什么不用 RFC 5987 那种 {@code filename*=UTF-8''...} 写法</b>：那套是为了在头部表达
 * "字符集"元信息，而这里只有一个身份字符串，多一层语法只会让 curl 调用方多一处踩坑。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class OperatorHeaderFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Operator";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String raw = request.getHeader(HEADER);
        if (raw == null || !raw.contains("%")) {
            chain.doFilter(request, response);
            return;
        }
        String decoded;
        try {
            decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // 编码坏了就按原样用：一个只有半截 % 的名字，不该让整个请求 500
            chain.doFilter(request, response);
            return;
        }
        chain.doFilter(new DecodedOperatorRequest(request, decoded), response);
    }

    /** 只覆盖这一个头，其余全部转给原请求。 */
    static final class DecodedOperatorRequest extends HttpServletRequestWrapper {

        private final String operator;

        DecodedOperatorRequest(HttpServletRequest request, String operator) {
            super(request);
            this.operator = operator;
        }

        @Override
        public String getHeader(String name) {
            return HEADER.equalsIgnoreCase(name) ? operator : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return HEADER.equalsIgnoreCase(name)
                    ? Collections.enumeration(List.of(operator))
                    : super.getHeaders(name);
        }
    }
}
