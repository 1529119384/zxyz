package uno.acloud.im.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.im.application.UserPresenceService;
import uno.acloud.im.config.ImAuthContext;
import uno.acloud.im.vo.UserPresenceVO;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 60 号 🟡-1 防回归：presence 批量探测面收敛——单次 ID 数上限（≤20）+ 同团队成员关系校验。
 */
@ExtendWith(MockitoExtension.class)
class PresenceControllerTest {

    private static final Long CURRENT_USER_ID = 7L;

    @Mock
    private UserPresenceService userPresenceService;

    private PresenceController controller;

    @BeforeEach
    void setUp() {
        controller = new PresenceController(userPresenceService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(ImAuthContext.USER_ID_ATTRIBUTE, CURRENT_USER_ID);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void listUserPresenceShouldDelegateFilteredIds() {
        when(userPresenceService.listPresence(CURRENT_USER_ID, List.of(3L, 5L))).thenReturn(List.of(
                new UserPresenceVO(3L, true, 1, null),
                new UserPresenceVO(5L, false, 0, null)
        ));

        List<UserPresenceVO> result = controller.listUserPresence("3, 5,").getData();

        assertEquals(2, result.size());
        verify(userPresenceService).listPresence(CURRENT_USER_ID, List.of(3L, 5L));
    }

    @Test
    void listUserPresenceShouldRejectMoreThan20Ids() {
        String userIds = "1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21";

        BusinessException exception = assertThrows(BusinessException.class,
                () -> controller.listUserPresence(userIds));

        assertEquals(ErrorCode.BAD_REQUEST, exception.getErrorCode());
        verify(userPresenceService, never()).listPresence(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.<List<Long>>any());
    }
}
