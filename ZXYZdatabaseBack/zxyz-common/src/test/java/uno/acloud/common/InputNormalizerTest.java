package uno.acloud.common;

import org.junit.jupiter.api.Test;
import uno.acloud.exception.BusinessException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InputNormalizerTest {

    @Test
    void optionalTextShouldTrimAndConvertBlankToNull() {
        assertNull(InputNormalizer.optionalText(null));
        assertNull(InputNormalizer.optionalText("   "));
        assertEquals("团队名称", InputNormalizer.optionalText("  团队名称  "));
    }

    @Test
    void requireTextShouldRejectBlankWithBadRequest() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> InputNormalizer.requireText(" ", "名称不能为空")
        );

        assertEquals(ErrorCode.BAD_REQUEST, exception.getErrorCode());
        assertEquals("名称不能为空", exception.getMessage());
    }

    @Test
    void optionalTextShouldCheckLengthAfterTrim() {
        assertEquals("abc", InputNormalizer.optionalText(" abc ", 3, "内容长度不能超过 3"));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> InputNormalizer.optionalText(" abcd ", 3, "内容长度不能超过 3")
        );

        assertEquals(ErrorCode.BAD_REQUEST, exception.getErrorCode());
        assertEquals("内容长度不能超过 3", exception.getMessage());
    }

    @Test
    void requireTextShouldKeepEmptyMessageBeforeLengthMessage() {
        BusinessException emptyException = assertThrows(
                BusinessException.class,
                () -> InputNormalizer.requireText(" ", "内容不能为空", 3, "内容长度不能超过 3")
        );
        BusinessException lengthException = assertThrows(
                BusinessException.class,
                () -> InputNormalizer.requireText(" abcd ", "内容不能为空", 3, "内容长度不能超过 3")
        );

        assertEquals("内容不能为空", emptyException.getMessage());
        assertEquals("内容长度不能超过 3", lengthException.getMessage());
    }
}
