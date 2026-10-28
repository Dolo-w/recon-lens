package io.github.dolow.reconlens.llm;

/**
 * token 估算：中日韩字符按 1 算，其它按 4 字符 1 个算。
 *
 * <p>只用于"装得下吗 / 该不该压缩"这类判断，不用于计费——所以宁可粗，也不引入 tokenizer。
 * 放在 llm 而不是 chat，因为提示词装配（{@code PromptGuard}）、预算账本（{@code TurnBudget}）、
 * 摘要触发（{@code ChatSummaryService}）三处都要用，谁先需要不该决定它住哪。
 */
public final class Tokens {

    private Tokens() {
    }

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int wide = 0;
        int narrow = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x2E80) {
                wide++;
            } else {
                narrow++;
            }
        }
        return wide + (narrow + 3) / 4;
    }
}
