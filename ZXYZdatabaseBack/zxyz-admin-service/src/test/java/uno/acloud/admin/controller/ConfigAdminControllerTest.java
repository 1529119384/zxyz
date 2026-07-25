package uno.acloud.admin.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.acloud.admin.domain.SysConfig;
import uno.acloud.admin.mapper.SysConfigAuditMapper;
import uno.acloud.admin.mapper.SysConfigMapper;
import uno.acloud.admin.service.ConfigService;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.Result;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConfigAdminControllerTest {

    @Mock
    private ConfigService configService;

    @Mock
    private SysConfigMapper configMapper;

    @Mock
    private SysConfigAuditMapper auditMapper;

    private ConfigAdminController controller;

    @BeforeEach
    void setUp() {
        controller = new ConfigAdminController(configService, configMapper, auditMapper);
    }

    // ---- listAll ----

    @Test
    void listAll_delegatesToMapper_returnsSuccess() {
        when(configMapper.selectList(any())).thenReturn(Collections.emptyList());

        Result<List<SysConfig>> result = controller.listAll();

        verify(configMapper).selectList(any());
        assertEquals(ErrorCode.SUCCESS, result.getCode());
    }

    // ---- getByKey ----

    @Test
    void getByKey_existingKey_returnsValue() {
        when(configService.get("app.name")).thenReturn("my-app");

        Result<String> result = controller.getByKey("app.name");

        verify(configService).get("app.name");
        assertEquals(ErrorCode.SUCCESS, result.getCode());
        assertEquals("my-app", result.getData());
    }

    @Test
    void getByKey_missingKey_returnsNotFound() {
        when(configService.get("nonexistent")).thenReturn(null);

        Result<String> result = controller.getByKey("nonexistent");

        verify(configService).get("nonexistent");
        assertEquals(ErrorCode.NOT_FOUND, result.getCode());
    }

    // ---- update ----

    @Test
    void update_validRequest_callsService() {
        ConfigAdminController.UpdateConfigRequest request = new ConfigAdminController.UpdateConfigRequest();
        request.setValue("new-value");

        Result<Void> result = controller.update("app.name", request, 1L);

        verify(configService).update("app.name", "new-value", 1L);
        assertEquals(ErrorCode.SUCCESS, result.getCode());
    }
}
