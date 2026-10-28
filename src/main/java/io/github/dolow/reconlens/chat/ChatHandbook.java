package io.github.dolow.reconlens.chat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 给对话模型的项目手册（{@code src/main/resources/chat/handbook.md}）。
 *
 * <p><b>为什么常驻一份，而不是把 docs 塞进去</b>：模型每轮都要知道"这个系统里有表/规则/任务/差异、
 * 写动作要人点、不许编列名"，这些是稳定语义，一轮约 1.5k token；而 docs 里的设计长文跟当轮无关，
 * 全塞进去每轮多两三万 token，换不来一次更好的判断。需要细节时让它用检索工具自己找。
 *
 * <p>读不到文件时退化成一句提示，不抛：手册是增强项，缺了对话照样能跑（只是更容易瞎猜）。
 */
@Component
public final class ChatHandbook {

    private static final Logger log = LoggerFactory.getLogger(ChatHandbook.class);
    static final String RESOURCE = "/chat/handbook.md";
    static final String FALLBACK = "（项目手册没有装载成功；只按工具清单和系统事实行事，缺信息就问用户。）";

    private final String text;

    public ChatHandbook() {
        this.text = read();
    }

    public String text() {
        return text;
    }

    private static String read() {
        try (InputStream in = ChatHandbook.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                log.warn("项目手册不在 classpath 上：{}", RESOURCE);
                return FALLBACK;
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
            return text.isEmpty() ? FALLBACK : text;
        } catch (Exception e) {
            log.warn("读项目手册失败：{}", e.toString());
            return FALLBACK;
        }
    }
}
