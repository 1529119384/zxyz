package uno.acloud.email.application;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmailTemplateRendererTest {

    private final EmailTemplateRenderer renderer = new EmailTemplateRenderer();

    @Test
    void renderHtmlShouldEscapeVariables() {
        String html = renderer.renderHtml("<p>{{content}}</p>", Map.of("content", "<b>hello</b>"));

        assertEquals("<p>&lt;b&gt;hello&lt;/b&gt;</p>", html);
    }

    @Test
    void renderSubjectShouldKeepPlainText() {
        String subject = renderer.renderSubject("通知：{{title}}", Map.of("title", "系统消息"));

        assertEquals("通知：系统消息", subject);
    }
}
