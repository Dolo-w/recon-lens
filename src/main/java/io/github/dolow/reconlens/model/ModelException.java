package io.github.dolow.reconlens.model;

/** 模型表构建失败：请求不合法或数据库不支持该方言。 */
public class ModelException extends RuntimeException {

    public ModelException(String message) {
        super(message);
    }

    public ModelException(String message, Throwable cause) {
        super(message, cause);
    }
}
