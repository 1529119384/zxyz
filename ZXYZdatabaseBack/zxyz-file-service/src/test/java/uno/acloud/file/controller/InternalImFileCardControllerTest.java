package uno.acloud.file.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import uno.acloud.common.GlobalExceptionHandler;
import uno.acloud.file.dto.im.FileCardEntryRequest;
import uno.acloud.file.dto.im.FileCardResolveRequest;
import uno.acloud.file.dto.im.FileCardSnapshotRequest;
import uno.acloud.file.service.ImFileCardPort;
import uno.acloud.file.vo.im.FileCardSnapshotVO;
import uno.acloud.file.web.ActingUserIdResolver;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link InternalImFileCardController} 内部端点测试（P1-1 修复的集成钉子）。
 *
 * <p>覆盖两条路径：
 * <b>有</b> {@code X-Acting-User-Id} → 操作者身份传入 port，主链路 200；
 * <b>无</b>（或非法）→ {@code BusinessException}(BAD_REQUEST) 明确报错 ——
 * 而不是修复前的 NotLoginException→401（内部链路断裂的原始形态）。</p>
 */
@ExtendWith(MockitoExtension.class)
class InternalImFileCardControllerTest {

    private static final String SNAPSHOT_URI = "/api/internal/im-file-cards/snapshot";
    private static final String RESOLVE_URI = "/api/internal/im-file-cards/resolve";

    @Mock
    private ImFileCardPort imFileCardPort;

    private MockMvc mockMvc;
    /** 与 MockMvc 消息转换器共用同一个 jsr310 mapper（LocalDateTime 序列化必需）。 */
    private ObjectMapper jsr310Mapper;

    @BeforeEach
    void setUp() {
        ActingUserIdResolver resolver = new ActingUserIdResolver();
        // 请求体含 LocalDateTime（FileCardEntryRequest.modifyTime）：standalone MockMvc 不带
        // Boot 的自动配置，必须显式挂 jsr310 模块，否则序列化请求体即失败。
        jsr310Mapper = new ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new InternalImFileCardController(imFileCardPort, resolver))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new org.springframework.http.converter.StringHttpMessageConverter(),
                        new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(jsr310Mapper))
                .build();
    }

    // ==================== snapshot：有头路径 ====================

    @Test
    void snapshot_withActingUserIdHeader_passesUserIdIntoPort() throws Exception {
        when(imFileCardPort.createSnapshot(org.mockito.ArgumentMatchers.any(FileCardSnapshotRequest.class), eq(42L)))
                .thenReturn(new FileCardSnapshotVO("MULTI_FILE", 42L, -1L, 2, List.of()));

        mockMvc.perform(post(SNAPSHOT_URI)
                        .header("X-Acting-User-Id", "42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileIds\":[11,12]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.data.ownerUserId").value(42));

        verify(imFileCardPort).createSnapshot(org.mockito.ArgumentMatchers.any(FileCardSnapshotRequest.class), eq(42L));
    }

    // ==================== snapshot：无头路径 ====================

    @Test
    void snapshot_withoutActingUserIdHeader_rejectedWithBusinessError() throws Exception {
        mockMvc.perform(post(SNAPSHOT_URI)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileIds\":[11,12]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(uno.acloud.common.ErrorCode.BAD_REQUEST))
                .andExpect(jsonPath("$.msg").exists());
    }

    @Test
    void snapshot_withMalformedActingUserIdHeader_rejectedWithBusinessError() throws Exception {
        mockMvc.perform(post(SNAPSHOT_URI)
                        .header("X-Acting-User-Id", "not-a-number")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileIds\":[11,12]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(uno.acloud.common.ErrorCode.BAD_REQUEST));
    }

    // ==================== resolve：有头路径 ====================

    @Test
    void resolve_withActingUserIdHeader_passesUserIdIntoPort() throws Exception {
        FileCardEntryRequest entry = new FileCardEntryRequest();
        entry.setFileId(11L);
        entry.setFileType(1);
        entry.setOriginalName("a.txt");
        entry.setParentId(-1L);
        entry.setStorePath("/p");
        entry.setModifyTime(LocalDateTime.of(2026, 4, 27, 13, 45, 12));
        FileCardResolveRequest request = new FileCardResolveRequest();
        request.setShareType("SINGLE_FILE");
        request.setOwnerUserId(42L);
        request.setEntries(List.of(entry));

        mockMvc.perform(post(RESOLVE_URI)
                        .header("X-Acting-User-Id", "42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsr310Mapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1));

        verify(imFileCardPort).resolve(org.mockito.ArgumentMatchers.any(FileCardResolveRequest.class), eq(42L));
    }

    @Test
    void resolve_withoutActingUserIdHeader_rejectedWithBusinessError() throws Exception {
        FileCardEntryRequest entry = new FileCardEntryRequest();
        entry.setFileId(11L);
        FileCardResolveRequest request = new FileCardResolveRequest();
        request.setShareType("SINGLE_FILE");
        request.setOwnerUserId(42L);
        request.setEntries(List.of(entry));

        mockMvc.perform(post(RESOLVE_URI)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsr310Mapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(uno.acloud.common.ErrorCode.BAD_REQUEST));
    }
}
