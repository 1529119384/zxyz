package uno.acloud.im.infrastructure.client;

import org.junit.jupiter.api.Test;
import uno.acloud.exception.BusinessException;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileCardClientTest {

    @Test
    void parseModifyTimeShouldSupportMainServiceDateTimePattern() {
        LocalDateTime result = FileCardClient.parseModifyTime("2026-04-27 13:45:12");

        assertEquals(LocalDateTime.of(2026, 4, 27, 13, 45, 12), result);
    }

    @Test
    void parseModifyTimeShouldKeepIsoCompatibility() {
        LocalDateTime result = FileCardClient.parseModifyTime("2026-04-27T13:45:12");

        assertEquals(LocalDateTime.of(2026, 4, 27, 13, 45, 12), result);
    }

    @Test
    void parseModifyTimeShouldHandleBlankValue() {
        assertNull(FileCardClient.parseModifyTime(" "));
        assertNull(FileCardClient.parseModifyTime(null));
    }

    @Test
    void parseModifyTimeShouldRejectUnknownPattern() {
        assertThrows(BusinessException.class, () -> FileCardClient.parseModifyTime("2026/04/27 13:45:12"));
    }
}
