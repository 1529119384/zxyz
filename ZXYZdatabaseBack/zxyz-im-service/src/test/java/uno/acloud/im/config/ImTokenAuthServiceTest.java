package uno.acloud.im.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.satoken.AuthServicePort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ImTokenAuthServiceTest {

    private AuthServicePort authServicePort;
    private ImTokenAuthService service;

    @BeforeEach
    void setUp() {
        authServicePort = mock(AuthServicePort.class);
        service = new ImTokenAuthService(authServicePort);
    }

    @Test
    void shouldResolveUserIdFromBearerAuthorization() {
        when(authServicePort.getLoginIdByToken("token-demo")).thenReturn(7L);

        Long userId = service.resolveUserIdFromAuthorization("Bearer token-demo");

        assertEquals(7L, userId);
    }

    @Test
    void shouldRejectMissingBearerPrefix() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.resolveUserIdFromAuthorization("token-demo"));

        assertEquals(ErrorCode.NO_LOGIN, exception.getErrorCode());
        assertEquals("NO_LOGIN", exception.getMessage());
    }

    @Test
    void shouldRejectInvalidToken() {
        when(authServicePort.getLoginIdByToken("bad-token"))
                .thenThrow(new RuntimeException("invalid"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.resolveUserIdByToken("bad-token"));

        assertEquals(ErrorCode.NO_LOGIN, exception.getErrorCode());
        assertEquals("NO_LOGIN", exception.getMessage());
    }
}
