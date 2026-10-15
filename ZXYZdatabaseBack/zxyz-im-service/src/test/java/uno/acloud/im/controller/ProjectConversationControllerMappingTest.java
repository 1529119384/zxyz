package uno.acloud.im.controller;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;

import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 60 号 🟠-1 防回归：项目会话端点必须挂在内部通道 /api/internal/im/projects/** 下。
 *
 * <p>背景：这组端点唯一调用方是 project-service 的 ImCollaborationClient（内部 token 鉴权），
 * 设计上就不该暴露给登录态前端。历史上挂在 /api/im/projects/** 仅有 @SaCheckLogin，
 * 任意登录用户可伪造项目申请消息 / 归档任意项目会话。若未来有人把 RequestMapping
 * 改回公开前缀（或新增端点漏挂内部前缀），本测试直接失败。</p>
 */
class ProjectConversationControllerMappingTest {

    private static final String INTERNAL_PREFIX = "/api/internal/im/projects";
    private static final String FORBIDDEN_PUBLIC_PREFIX = "/api/im/projects";

    @Test
    void allEndpointsMustBeUnderInternalPrefix() throws Exception {
        ProjectConversationController controller = new ProjectConversationController(null);
        for (Method method : ProjectConversationController.class.getDeclaredMethods()) {
            RequestMappingInfo info = buildMappingInfo(method);
            if (info == null) {
                continue;
            }
            Set<String> patterns = info.getPatternValues();
            assertFalse(patterns.isEmpty(), "端点 " + method.getName() + " 必须有 RequestMapping");
            for (String pattern : patterns) {
                assertTrue(pattern.startsWith(INTERNAL_PREFIX),
                        "端点 " + method.getName() + " 的路径 " + pattern + " 必须挂在内部前缀 " + INTERNAL_PREFIX + " 下");
                assertFalse(pattern.startsWith(FORBIDDEN_PUBLIC_PREFIX),
                        "端点 " + method.getName() + " 的路径 " + pattern
                                + " 不得回落公开前缀 " + FORBIDDEN_PUBLIC_PREFIX + "（60 号 🟠-1 回归）");
            }
        }
    }

    @Test
    void archivedPublicMappingMustNotResurface() {
        // 钉住历史事故形态：公开前缀 /api/im/projects 下的四个端点全部消失。
        org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping mapping =
                new org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping();
        mapping.getHandlerMethods().forEach((info, handlerMethod) -> {
            if (!ProjectConversationController.class.equals(handlerMethod.getBeanType())) {
                return;
            }
            for (String pattern : info.getPatternValues()) {
                assertFalse(pattern.startsWith(FORBIDDEN_PUBLIC_PREFIX),
                        "项目会话端点 " + pattern + " 重新暴露在公开前缀下（60 号 🟠-1 回归）");
            }
        });
    }

    /**
     * 参照 spring-web 对注解元数据的解析方式构建 RequestMappingInfo；
     * 单元测试环境下无 Web 容器，用 AnnotatedElementUtils 直接读注解属性。
     */
    private RequestMappingInfo buildMappingInfo(Method method) {
        org.springframework.web.bind.annotation.RequestMapping classMapping =
                ProjectConversationController.class.getAnnotation(org.springframework.web.bind.annotation.RequestMapping.class);
        if (classMapping == null) {
            return null;
        }
        org.springframework.web.bind.annotation.RequestMapping methodMapping =
                method.getAnnotation(org.springframework.web.bind.annotation.RequestMapping.class);
        if (methodMapping == null) {
            return null;
        }
        String path = classMapping.value()[0] + (methodMapping.value().length > 0 ? methodMapping.value()[0] : "");
        return RequestMappingInfo
                .paths(path)
                .methods(RequestMethod.values())
                .build();
    }
}
