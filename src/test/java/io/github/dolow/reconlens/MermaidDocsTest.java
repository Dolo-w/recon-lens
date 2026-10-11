package io.github.dolow.reconlens;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 守住「本地 markdown 看着没事、GitHub 上 mermaid 直接渲染失败」这一类问题。
 *
 * <p>踩过的坑：边标签里写了 {@code {shards} {index}}，mermaid 把 {@code &#123;} 当成 diamond 节点的起始符，
 * 期待闭合却等来 {@code |}，于是整张图渲染不出来，GitHub 上显示
 * {@code Parse error on line 8: ... got 'DIAMOND_START'}（见 README 的架构图）。
 *
 * <p>标签文本里的花括号一律不安全；而合法的 diamond 节点写法是「节点标识符紧跟左花括号」
 * （例如 {@code B{金额一致？}}）。所以这里先去掉合法的 diamond 写法，再断言剩下的图里没有花括号。
 */
class MermaidDocsTest {

    /** ```mermaid ... ``` 代码块（GitHub 只认这个围栏标记）。 */
    private static final Pattern MERMAID_BLOCK = Pattern.compile("```mermaid\\r?\\n(.*?)```", Pattern.DOTALL);

    /** 合法的 diamond 节点：标识符紧跟 { ... }。 */
    private static final Pattern DIAMOND_NODE = Pattern.compile("[A-Za-z0-9_\\-]+\\{[^}\\r\\n]*}");

    @Test
    void mermaidBlocksStayRenderableOnGitHub() throws IOException {
        Path root = projectRoot();
        int blocks = 0;
        for (Path document : markdownDocuments(root)) {
            Matcher matcher = MERMAID_BLOCK.matcher(Files.readString(document, StandardCharsets.UTF_8));
            while (matcher.find()) {
                blocks++;
                String risky = DIAMOND_NODE.matcher(matcher.group(1)).replaceAll("");
                assertThat(risky)
                        .as(
                                "%s 的第 %d 个 mermaid 图里有花括号：花括号在标签文本里会被当成 diamond 节点起始符，"
                                        + "mermaid 解析失败、GitHub 上整张图渲染不出来。用文字描述代替，例如把 {shards} 写成 shards 占位符",
                                root.relativize(document), blocks)
                        .doesNotContain("{")
                        .doesNotContain("}");
            }
        }
        // 图被改名或搬走后上面的断言会空转，这里守住「确实检查到了图」。
        assertThat(blocks).as("没有在任何 markdown 里找到 ```mermaid 代码块，守卫已失效").isPositive();
    }

    /** surefire 的工作目录就是项目根目录。 */
    private static Path projectRoot() {
        return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    /** 根目录的 markdown 加 docs/ 下的 markdown（README 与文档都算对外渲染的内容）。 */
    private static List<Path> markdownDocuments(Path root) throws IOException {
        List<Path> documents = new ArrayList<>();
        try (Stream<Path> top = Files.list(root)) {
            top.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .forEach(documents::add);
        }
        Path docs = root.resolve("docs");
        if (Files.isDirectory(docs)) {
            try (Stream<Path> nested = Files.walk(docs)) {
                nested.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".md"))
                        .forEach(documents::add);
            }
        }
        return documents;
    }
}
