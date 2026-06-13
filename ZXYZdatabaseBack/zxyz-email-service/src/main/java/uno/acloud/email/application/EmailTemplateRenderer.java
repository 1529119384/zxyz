package uno.acloud.email.application;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class EmailTemplateRenderer {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_\\-.]+)\\s*}}");

    public String renderSubject(String template, Map<String, ?> variables) {
        return render(template, variables, false);
    }

    public String renderHtml(String template, Map<String, ?> variables) {
        return render(template, variables, true);
    }

    private String render(String template, Map<String, ?> variables, boolean htmlEscape) {
        if (template == null) {
            return "";
        }
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            Object rawValue = variables == null ? null : variables.get(matcher.group(1));
            String value = rawValue == null ? "" : String.valueOf(rawValue);
            matcher.appendReplacement(result, Matcher.quoteReplacement(htmlEscape ? escapeHtml(value) : value));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String escapeHtml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
