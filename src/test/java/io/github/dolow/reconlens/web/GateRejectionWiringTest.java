package io.github.dolow.reconlens.web;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.github.dolow.reconlens.dsl.RuleException;
import io.github.dolow.reconlens.dsl.RuleGateRejectedException;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 谁处理 {@code RuleException}，谁就必须先拦 {@code RuleGateRejectedException}。
 *
 * <p>这不是风格问题，是一个**已经踩过一次的坑**：{@code RuleGateRejectedException} 继承
 * {@code RuleException}，而 controller 内部的 {@code @ExceptionHandler} 比
 * {@code @RestControllerAdvice} 优先 —— 于是任何"顺手加了个 RuleException → 400"的 controller
 * 都会把闸门拒绝悄悄降级成 400，调用方再也分不清"改规则"和"改请求"。
 *
 * <p>所以把这个先后关系写成测试：以后新加 controller 忘了这一道，测试当场红，而不是等人去
 * 线上发现"为什么 422 变成了 400"。
 */
class GateRejectionWiringTest {

    @Test
    void 处理RuleException的控制器都必须先拦闸门拒绝() {
        JavaClasses classes = new ClassFileImporter().importPackages("io.github.dolow.reconlens.web");

        int checked = 0;
        for (JavaClass javaClass : classes) {
            Class<?> raw;
            try {
                raw = javaClass.reflect();
            } catch (RuntimeException e) {
                continue; // 内部类/无法反射的，交给 ArchUnit 的其它规则
            }
            if (!raw.isAnnotationPresent(RestController.class)) {
                continue;
            }
            if (!handles(raw, RuleException.class)) {
                continue;
            }
            checked++;
            assertThat(handles(raw, RuleGateRejectedException.class))
                    .as("%s 处理了 RuleException，但没有为 RuleGateRejectedException 单独声明 handler"
                            + "（controller 内的 handler 比 advice 优先，闸门拒绝会被降级成 400）",
                            raw.getSimpleName())
                    .isTrue();
        }

        // 至少得真的扫到几个 controller：集合为空时上面的断言全是空转
        assertThat(checked)
                .as("没有任何 controller 命中 RuleException 处理器，说明扫描口径变了（不是好事）")
                .isGreaterThanOrEqualTo(3);
    }

    private static boolean handles(Class<?> type, Class<? extends Throwable> target) {
        for (Method method : type.getDeclaredMethods()) {
            ExceptionHandler handler = method.getAnnotation(ExceptionHandler.class);
            if (handler == null) {
                continue;
            }
            for (Class<? extends Throwable> handled : handler.value()) {
                if (handled.equals(target)) {
                    return true;
                }
            }
        }
        return false;
    }
}
