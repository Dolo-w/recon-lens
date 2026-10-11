package io.github.dolow.reconlens.dsl;

/** 规则配置错误。规则是配置而不是代码，所以错误信息必须能直接告诉用户改哪一行。 */
public class RuleException extends RuntimeException {

    public RuleException(String message) {
        super(message);
    }

    public static RuleException at(String source, String message) {
        return new RuleException("规则文件 " + source + " 配置错误: " + message);
    }
}
