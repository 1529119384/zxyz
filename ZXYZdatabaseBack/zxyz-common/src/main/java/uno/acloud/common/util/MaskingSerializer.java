package uno.acloud.common.util;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;

public class MaskingSerializer extends JsonSerializer<String> {

    private final String maskPattern;

    public MaskingSerializer(String maskPattern) {
        this.maskPattern = maskPattern;
    }

    @Override
    public void serialize(String value, JsonGenerator gen, SerializerProvider provider) throws IOException {
        if (value == null) {
            gen.writeNull();
            return;
        }
        gen.writeString(mask(value));
    }

    private String mask(String value) {
        if (value.isEmpty()) {
            return value;
        }
        if (value.contains("@")) {
            return maskEmail(value);
        }
        return maskPhone(value);
    }

    private String maskEmail(String email) {
        int atIndex = email.indexOf('@');
        if (atIndex <= 0) {
            return "***" + email.substring(atIndex);
        }
        String local = email.substring(0, atIndex);
        String domain = email.substring(atIndex);
        if (local.length() <= 1) {
            return "*" + domain;
        }
        return local.charAt(0) + "***" + domain;
    }

    private String maskPhone(String phone) {
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.length() < 7) {
            return phone.replaceAll(".", "*");
        }
        int start = phone.indexOf(digits.charAt(0));
        String prefix = phone.substring(0, start);
        return prefix + digits.substring(0, 3) + "****" + digits.substring(digits.length() - 4);
    }
}
