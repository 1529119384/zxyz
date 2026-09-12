package uno.acloud.file.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.FileNodeType;
import uno.acloud.exception.BusinessException;
import uno.acloud.file.config.ServiceProperties;
import uno.acloud.file.dto.BatchConfirmUploadRequest;
import uno.acloud.file.dto.ConfirmUploadRequest;
import uno.acloud.file.infrastructure.entity.FileItem;
import uno.acloud.file.infrastructure.entity.Folder;
import uno.acloud.file.infrastructure.mapper.UsageLedgerMapper;
import uno.acloud.file.storage.StorageProvider;
import uno.acloud.file.storage.StorageProviderRegistry;
import uno.acloud.file.storage.UploadInfo;
import uno.acloud.file.storage.DownloadInfo;
import uno.acloud.file.vo.BatchUploadConfirmResultVO;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FileUploadServiceTest {

    @Mock
    private StorageProviderRegistry registry;

    @Mock
    private StorageProvider defaultProvider;

    @Mock
    private FileUploadPersistenceManager fileUploadPersistenceService;

    @Mock
    private FileDomainValidator fileDomainValidator;

    @Mock
    private FilePathResolver filePathResolver;

    @Mock
    private FileAccessGuard fileAccessGuardService;

    @Mock
    private RestClient restClient;

    @Mock
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Mock
    private UsageLedgerMapper usageLedgerMapper;

    /** 2.1.2：上传归属凭证用的 Redis 模板（objectKey → userId） */
    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private ServiceProperties serviceProperties;

    private FileUploadService fileUploadService;

    @BeforeEach
    void setUp() {
        serviceProperties = new ServiceProperties();
        // Default: no quota service URL configured (skip HTTP quota check)
        serviceProperties.getProjectService().setBaseUrl(null);
        serviceProperties.setInternalServiceToken("test-token");

        when(registry.getDefaultProvider()).thenReturn(defaultProvider);
        // 默认：归属凭证存在且属于 userId=1（绝大多数用例的操作者）
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn("1");

        fileUploadService = new FileUploadService(
                registry, fileUploadPersistenceService, fileDomainValidator,
                filePathResolver, fileAccessGuardService, restClient,
                objectMapper, serviceProperties, usageLedgerMapper, redisTemplate, "", "", 524288000L);
    }

    // ==================== Upload with sufficient quota — should succeed ====================

    @Test
    void confirmUpload_sufficientQuota_shouldSucceed() {
        Long userId = 1L;
        Long parentId = 100L;
        Long teamId = 10L;

        ConfirmUploadRequest item = new ConfirmUploadRequest();
        item.setObjectKey("files/uuid-test.txt");
        item.setOriginalName("test.txt");
        item.setFileSize(1024L);
        item.setParentId(parentId);
        item.setTeamId(teamId);
        item.setSpaceType(uno.acloud.common.FileSpaceType.TEAM);

        BatchConfirmUploadRequest request = new BatchConfirmUploadRequest();
        request.setTeamId(teamId);
        request.setSpaceType(uno.acloud.common.FileSpaceType.TEAM);
        request.setFiles(List.of(item));

        Folder parentFolder = Folder.create();
        parentFolder.setId(parentId);
        parentFolder.setTeamId(teamId);
        parentFolder.setSpaceType(uno.acloud.common.FileSpaceType.TEAM);

        FileItem savedFile = FileItem.create();
        savedFile.setId(1000L);
        savedFile.setOriginalName("test.txt");

        when(fileDomainValidator.requireFolder(parentId)).thenReturn(parentFolder);
        when(fileDomainValidator.resolveAvailableName(
                eq(parentId), any(SpaceTarget.class), eq(FileNodeType.FILE),
                eq("test.txt"), anySet(), any()))
                .thenReturn("test.txt");
        when(defaultProvider.generateDownloadInfo(eq("files/uuid-test.txt"), eq("test.txt")))
                .thenReturn(new DownloadInfo(
                        "oss", "https://oss.example.com/files/uuid-test.txt",
                        "test.txt", true));
        when(fileUploadPersistenceService.saveFileItem(any(FileItem.class))).thenReturn(savedFile);
        // getObjectSize 是 fail-closed：未命中将拒绝确认。须与实际 fileSize 一致（1024）
        when(defaultProvider.getObjectSize("files/uuid-test.txt")).thenReturn(1024L);

        BatchUploadConfirmResultVO result = fileUploadService.confirmUpload(request, userId);

        assertNotNull(result);
        assertEquals(1, result.getTotalCount());
        assertEquals(1, result.getSuccessCount());
        assertEquals(0, result.getFailCount());
        verify(fileUploadPersistenceService).saveFileItem(any(FileItem.class));
        // 确认成功后应消费归属凭证，使同一凭证无法再次确认（2.1.2）
        verify(redisTemplate).delete("file:upload-owner:files/uuid-test.txt");
    }

    // ==================== Upload exceeding quota — should throw ====================

    @Test
    void confirmUpload_exceedingQuota_shouldThrow() {
        Long userId = 1L;

        ConfirmUploadRequest item = new ConfirmUploadRequest();
        item.setObjectKey("files/uuid-big.txt");
        item.setOriginalName("big.txt");
        item.setFileSize(10_000_000_000L);
        item.setParentId(100L);
        item.setTeamId(10L);
        item.setSpaceType(uno.acloud.common.FileSpaceType.TEAM);

        BatchConfirmUploadRequest request = new BatchConfirmUploadRequest();
        request.setTeamId(10L);
        request.setSpaceType(uno.acloud.common.FileSpaceType.TEAM);
        request.setFiles(List.of(item));

        // Must create service AFTER setting the URL, since it's captured at construction
        ServiceProperties quotaProps = new ServiceProperties();
        quotaProps.getProjectService().setBaseUrl("http://project-service:18080");
        quotaProps.setInternalServiceToken("test-token");

        // Throw 403 directly from post() — the catch block catches RestClientResponseException
        doThrow(HttpClientErrorException.create(
                HttpStatus.FORBIDDEN, "Forbidden",
                HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8))
                .when(restClient).post();

        FileUploadService quotaService = new FileUploadService(
                registry, fileUploadPersistenceService, fileDomainValidator,
                filePathResolver, fileAccessGuardService, restClient,
                objectMapper, quotaProps, usageLedgerMapper, redisTemplate, "", "", 524288000L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> quotaService.confirmUpload(request, userId));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("存储空间不足"));
    }

    // ==================== Upload to project space without access — should return fail ====================

    @Test
    void confirmUpload_projectSpaceWithoutAccess_shouldReturnFail() {
        Long userId = 1L;
        Long parentId = 100L;
        Long projectId = 50L;

        ConfirmUploadRequest item = new ConfirmUploadRequest();
        item.setObjectKey("files/uuid-test.txt");
        item.setOriginalName("test.txt");
        item.setFileSize(1024L);
        item.setParentId(parentId);
        item.setSpaceType(uno.acloud.common.FileSpaceType.PROJECT);
        item.setProjectId(projectId);

        BatchConfirmUploadRequest request = new BatchConfirmUploadRequest();
        request.setSpaceType(uno.acloud.common.FileSpaceType.PROJECT);
        request.setProjectId(projectId);
        request.setFiles(List.of(item));

        Folder parentFolder = Folder.create();
        parentFolder.setId(parentId);
        parentFolder.setTeamId(10L);
        parentFolder.setSpaceType(uno.acloud.common.FileSpaceType.PROJECT);
        parentFolder.setProjectId(projectId);

        when(fileDomainValidator.requireFolder(parentId)).thenReturn(parentFolder);
        // Project access check throws
        doThrow(new BusinessException(ErrorCode.NO_PERMISSION, "只有项目成员可以访问项目文件"))
                .when(fileAccessGuardService).requireProjectFileAccess(projectId, userId);

        BatchUploadConfirmResultVO result = fileUploadService.confirmUpload(request, userId);

        assertNotNull(result);
        assertEquals(1, result.getTotalCount());
        assertEquals(0, result.getSuccessCount());
        assertEquals(1, result.getFailCount());
    }

    // ==================== Upload with zero bytes — should handle gracefully ====================

    @Test
    void confirmUpload_zeroBytes_shouldSucceed() {
        Long userId = 1L;
        Long parentId = 100L;
        Long teamId = 10L;

        ConfirmUploadRequest item = new ConfirmUploadRequest();
        item.setObjectKey("files/uuid-empty.txt");
        item.setOriginalName("empty.txt");
        item.setFileSize(0L);
        item.setParentId(parentId);
        item.setTeamId(teamId);
        item.setSpaceType(uno.acloud.common.FileSpaceType.TEAM);

        BatchConfirmUploadRequest request = new BatchConfirmUploadRequest();
        request.setTeamId(teamId);
        request.setSpaceType(uno.acloud.common.FileSpaceType.TEAM);
        request.setFiles(List.of(item));

        Folder parentFolder = Folder.create();
        parentFolder.setId(parentId);
        parentFolder.setTeamId(teamId);
        parentFolder.setSpaceType(uno.acloud.common.FileSpaceType.TEAM);

        FileItem savedFile = FileItem.create();
        savedFile.setId(1001L);
        savedFile.setOriginalName("empty.txt");

        when(fileDomainValidator.requireFolder(parentId)).thenReturn(parentFolder);
        when(fileDomainValidator.resolveAvailableName(
                eq(parentId), any(SpaceTarget.class), eq(FileNodeType.FILE),
                eq("empty.txt"), anySet(), any()))
                .thenReturn("empty.txt");
        when(defaultProvider.generateDownloadInfo(eq("files/uuid-empty.txt"), eq("empty.txt")))
                .thenReturn(new DownloadInfo(
                        "oss", "https://oss.example.com/files/uuid-empty.txt",
                        "empty.txt", true));
        when(fileUploadPersistenceService.saveFileItem(any(FileItem.class))).thenReturn(savedFile);

        BatchUploadConfirmResultVO result = fileUploadService.confirmUpload(request, userId);

        assertNotNull(result);
        assertEquals(1, result.getTotalCount());
        assertEquals(1, result.getSuccessCount());
        assertEquals(0, result.getFailCount());
    }

    // ==================== Whitelist: getUploadSign rejects unsupported extension ====================

    @Test
    void getUploadSign_unsupportedExtension_shouldThrow() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> fileUploadService.getUploadSign("malware.xyz", 1L));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("不支持的文件类型: .xyz"));
    }

    @Test
    void getUploadSign_noExtension_shouldThrow() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> fileUploadService.getUploadSign("README", 1L));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("缺少扩展名"));
    }

    @Test
    void getUploadSign_allowedExtension_shouldSucceed() {
        when(fileDomainValidator.validateInputName("report.pdf")).thenReturn("report.pdf");
        when(defaultProvider.generateUploadInfo(anyString(), eq("report.pdf")))
                .thenReturn(new UploadInfo(
                        "oss", "https://oss.example.com/put", "files/uuid-report.pdf",
                        "https://oss.example.com/files/uuid-report.pdf",
                        "application/pdf", "attachment", 1700000000L, true));

        UploadInfo result = fileUploadService.getUploadSign("report.pdf", 1L);
        assertNotNull(result);
    }

    // ==================== Whitelist: confirmUpload rejects unsupported extension ====================

    @Test
    void confirmUpload_unsupportedExtension_shouldReturnFail() {
        Long userId = 1L;
        Long parentId = 100L;
        Long teamId = 10L;

        ConfirmUploadRequest item = new ConfirmUploadRequest();
        item.setObjectKey("files/uuid-bad.xyz");
        item.setOriginalName("bad.xyz");
        item.setFileSize(1024L);
        item.setParentId(parentId);
        item.setTeamId(teamId);
        item.setSpaceType(uno.acloud.common.FileSpaceType.TEAM);

        BatchConfirmUploadRequest request = new BatchConfirmUploadRequest();
        request.setTeamId(teamId);
        request.setSpaceType(uno.acloud.common.FileSpaceType.TEAM);
        request.setFiles(List.of(item));

        BatchUploadConfirmResultVO result = fileUploadService.confirmUpload(request, userId);

        assertNotNull(result);
        assertEquals(1, result.getTotalCount());
        assertEquals(0, result.getSuccessCount());
        assertEquals(1, result.getFailCount());
    }

    // ==================== Max file size: confirmUpload rejects oversized file ====================

    @Test
    void confirmUpload_exceedingMaxSize_shouldReturnFail() {
        Long userId = 1L;
        Long parentId = 100L;
        Long teamId = 10L;

        ConfirmUploadRequest item = new ConfirmUploadRequest();
        item.setObjectKey("files/uuid-huge.txt");
        item.setOriginalName("huge.txt");
        item.setFileSize(600 * 1024 * 1024L); // 600MB, exceeds 500MB limit
        item.setParentId(parentId);
        item.setTeamId(teamId);
        item.setSpaceType(uno.acloud.common.FileSpaceType.TEAM);

        BatchConfirmUploadRequest request = new BatchConfirmUploadRequest();
        request.setTeamId(teamId);
        request.setSpaceType(uno.acloud.common.FileSpaceType.TEAM);
        request.setFiles(List.of(item));

        BatchUploadConfirmResultVO result = fileUploadService.confirmUpload(request, userId);

        assertNotNull(result);
        assertEquals(1, result.getTotalCount());
        assertEquals(0, result.getSuccessCount());
        assertEquals(1, result.getFailCount());
    }

    // ==================== OSS HEAD size check: confirmUpload rejects oversized OSS object ====================

    @Test
    void confirmUpload_ossObjectExceedsMaxSize_shouldReturnFail() {
        Long userId = 1L;
        Long parentId = 100L;
        Long teamId = 10L;

        ConfirmUploadRequest item = new ConfirmUploadRequest();
        item.setObjectKey("files/uuid-tampered.txt");
        item.setOriginalName("tampered.txt");
        item.setFileSize(1024L); // client reports small size
        item.setParentId(parentId);
        item.setTeamId(teamId);
        item.setSpaceType(uno.acloud.common.FileSpaceType.TEAM);

        BatchConfirmUploadRequest request = new BatchConfirmUploadRequest();
        request.setTeamId(teamId);
        request.setSpaceType(uno.acloud.common.FileSpaceType.TEAM);
        request.setFiles(List.of(item));

        // OSS HEAD returns actual size exceeding limit
        when(defaultProvider.getObjectSize("files/uuid-tampered.txt")).thenReturn(600 * 1024 * 1024L);

        BatchUploadConfirmResultVO result = fileUploadService.confirmUpload(request, userId);

        assertNotNull(result);
        assertEquals(1, result.getTotalCount());
        assertEquals(0, result.getSuccessCount());
        assertEquals(1, result.getFailCount());
    }

    // ==================== directUpload: valid upload should succeed ====================

    @Test
    void directUpload_validFile_shouldSucceed() throws Exception {
        Long userId = 1L;
        Long parentId = 100L;
        Long teamId = 10L;

        when(fileDomainValidator.validateInputName("test.txt")).thenReturn("test.txt");
        when(defaultProvider.supportsPresignedUpload()).thenReturn(false);
        when(defaultProvider.receiveUpload(anyString(), any(), anyString(), anyString())).thenReturn(1024L);
        when(defaultProvider.generateDownloadInfo(anyString(), anyString()))
                .thenReturn(new DownloadInfo("local", "/download/files/uuid-test.txt", "test.txt", true));
        when(defaultProvider.providerId()).thenReturn("local");

        Folder parentFolder = Folder.create();
        parentFolder.setId(parentId);
        parentFolder.setTeamId(teamId);
        parentFolder.setSpaceType(uno.acloud.common.FileSpaceType.TEAM);
        when(fileDomainValidator.requireFolder(parentId)).thenReturn(parentFolder);

        FileItem savedFile = FileItem.create();
        savedFile.setId(2000L);
        savedFile.setOriginalName("test.txt");
        when(fileUploadPersistenceService.saveFileItem(any(FileItem.class))).thenReturn(savedFile);

        java.io.ByteArrayInputStream inputStream = new java.io.ByteArrayInputStream("hello".getBytes());

        UploadInfo result = fileUploadService.directUpload(
                "test.txt", inputStream, "text/plain", parentId, userId, teamId, 1, null, 1024L);

        assertNotNull(result);
        assertEquals("local", result.getProvider());
        verify(fileUploadPersistenceService).saveFileItem(any(FileItem.class));
    }

    // ==================== directUpload: fileSize exceeds limit should throw ====================

    @Test
    void directUpload_exceedingMaxSize_shouldThrow() {
        Long userId = 1L;
        Long parentId = 100L;

        when(fileDomainValidator.validateInputName("huge.txt")).thenReturn("huge.txt");

        java.io.ByteArrayInputStream inputStream = new java.io.ByteArrayInputStream(new byte[0]);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> fileUploadService.directUpload(
                        "huge.txt", inputStream, "text/plain", parentId, userId, null, null, null,
                        600 * 1024 * 1024L));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("文件大小超过限制"));
    }

    // ==================== directUpload: insufficient quota should throw ====================

    @Test
    void directUpload_insufficientQuota_shouldThrow() {
        Long userId = 1L;
        Long parentId = 100L;
        Long teamId = 10L;

        ServiceProperties quotaProps = new ServiceProperties();
        quotaProps.getProjectService().setBaseUrl("http://project-service:18080");
        quotaProps.setInternalServiceToken("test-token");

        doThrow(HttpClientErrorException.create(
                HttpStatus.FORBIDDEN, "Forbidden",
                HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8))
                .when(restClient).post();

        FileUploadService quotaService = new FileUploadService(
                registry, fileUploadPersistenceService, fileDomainValidator,
                filePathResolver, fileAccessGuardService, restClient,
                objectMapper, quotaProps, usageLedgerMapper, redisTemplate, "", "", 524288000L);

        when(fileDomainValidator.validateInputName("quota-test.txt")).thenReturn("quota-test.txt");
        when(defaultProvider.supportsPresignedUpload()).thenReturn(false);
        when(defaultProvider.receiveUpload(anyString(), any(), anyString(), anyString())).thenReturn(1024L);
        when(defaultProvider.generateDownloadInfo(anyString(), anyString()))
                .thenReturn(new DownloadInfo("local", "/download/files/uuid-quota.txt", "quota-test.txt", true));
        when(defaultProvider.providerId()).thenReturn("local");

        Folder parentFolder = Folder.create();
        parentFolder.setId(parentId);
        parentFolder.setTeamId(teamId);
        parentFolder.setSpaceType(uno.acloud.common.FileSpaceType.TEAM);
        when(fileDomainValidator.requireFolder(parentId)).thenReturn(parentFolder);

        java.io.ByteArrayInputStream inputStream = new java.io.ByteArrayInputStream("hello".getBytes());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> quotaService.directUpload(
                        "quota-test.txt", inputStream, "text/plain", parentId, userId, teamId, 1, null, 1024L));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("存储空间不足"));
    }

    // ==================== directUpload: fileSize passed to saveFileInfo ====================

    @Test
    void directUpload_fileSizePassedToSaveFileInfo() throws Exception {
        Long userId = 1L;
        Long parentId = 100L;
        Long teamId = 10L;
        long expectedSize = 2048L;

        when(fileDomainValidator.validateInputName("sized.txt")).thenReturn("sized.txt");
        when(defaultProvider.supportsPresignedUpload()).thenReturn(false);
        when(defaultProvider.receiveUpload(anyString(), any(), anyString(), anyString())).thenReturn(expectedSize);
        when(defaultProvider.generateDownloadInfo(anyString(), anyString()))
                .thenReturn(new DownloadInfo("local", "/download/files/uuid-sized.txt", "sized.txt", true));
        when(defaultProvider.providerId()).thenReturn("local");

        Folder parentFolder = Folder.create();
        parentFolder.setId(parentId);
        parentFolder.setTeamId(teamId);
        parentFolder.setSpaceType(uno.acloud.common.FileSpaceType.TEAM);
        when(fileDomainValidator.requireFolder(parentId)).thenReturn(parentFolder);

        FileItem savedFile = FileItem.create();
        savedFile.setId(3000L);
        savedFile.setOriginalName("sized.txt");
        when(fileUploadPersistenceService.saveFileItem(any(FileItem.class))).thenAnswer(invocation -> {
            FileItem item = invocation.getArgument(0);
            assertEquals(expectedSize, item.getFileSize());
            return savedFile;
        });

        java.io.ByteArrayInputStream inputStream = new java.io.ByteArrayInputStream("hello".getBytes());

        UploadInfo result = fileUploadService.directUpload(
                "sized.txt", inputStream, "text/plain", parentId, userId, teamId, 1, null, expectedSize);

        assertNotNull(result);
        verify(fileUploadPersistenceService).saveFileItem(any(FileItem.class));
    }

    // ==================== 2.1.2：上传归属凭证（objectKey → userId） ====================

    @Test
    void getUploadSign_registersOwnerBinding() {
        when(fileDomainValidator.validateInputName("report.pdf")).thenReturn("report.pdf");
        when(defaultProvider.generateUploadInfo(anyString(), eq("report.pdf")))
                .thenReturn(new UploadInfo(
                        "oss", "https://oss.example.com/put", "files/uuid-report.pdf",
                        "https://oss.example.com/files/uuid-report.pdf",
                        "application/pdf", "attachment", 1700000000L, true));

        fileUploadService.getUploadSign("report.pdf", 7L);

        verify(valueOperations).set(
                eq("file:upload-owner:files/uuid-report.pdf"),
                eq("7"),
                any(java.time.Duration.class));
    }

    @Test
    void getUploadSign_withoutExpireAt_shouldStillRegister() {
        when(fileDomainValidator.validateInputName("report.pdf")).thenReturn("report.pdf");
        when(defaultProvider.generateUploadInfo(anyString(), eq("report.pdf")))
                .thenReturn(new UploadInfo(
                        "oss", "https://oss.example.com/put", "files/uuid-report.pdf",
                        null, "application/pdf", "attachment", null, false));

        fileUploadService.getUploadSign("report.pdf", 7L);

        verify(valueOperations).set(anyString(), eq("7"), any(java.time.Duration.class));
    }

    @Test
    void getUploadSign_redisFailure_shouldNotBlockSigning() {
        when(fileDomainValidator.validateInputName("report.pdf")).thenReturn("report.pdf");
        when(defaultProvider.generateUploadInfo(anyString(), eq("report.pdf")))
                .thenReturn(new UploadInfo(
                        "oss", "https://oss.example.com/put", "files/uuid-report.pdf",
                        null, "application/pdf", "attachment", 1700000000L, true));
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("redis down"));

        UploadInfo result = fileUploadService.getUploadSign("report.pdf", 7L);

        // 登记失败不阻断签名：确认阶段会 fail-closed 拒绝
        assertNotNull(result);
    }

    @Test
    void confirmUpload_withoutOwnerBinding_shouldReturnFail() {
        when(valueOperations.get(anyString())).thenReturn(null);
        BatchConfirmUploadRequest request = teamConfirmRequest("files/uuid-orphan.txt", "orphan.txt");

        BatchUploadConfirmResultVO result = fileUploadService.confirmUpload(request, 1L);

        assertEquals(1, result.getTotalCount());
        assertEquals(0, result.getSuccessCount());
        assertEquals(1, result.getFailCount());
        assertTrue(result.getItems().get(0).getMsg().contains("上传凭证"));
        verify(fileUploadPersistenceService, never()).saveFileItem(any(FileItem.class));
    }

    @Test
    void confirmUpload_ownerMismatch_shouldReturnFail() {
        when(valueOperations.get(anyString())).thenReturn("999");
        BatchConfirmUploadRequest request = teamConfirmRequest("files/uuid-steal.txt", "steal.txt");

        BatchUploadConfirmResultVO result = fileUploadService.confirmUpload(request, 1L);

        assertEquals(1, result.getTotalCount());
        assertEquals(0, result.getSuccessCount());
        assertEquals(1, result.getFailCount());
        assertEquals(ErrorCode.NO_PERMISSION, result.getItems().get(0).getCode());
        verify(fileUploadPersistenceService, never()).saveFileItem(any(FileItem.class));
    }

    @Test
    void confirmUpload_ownerBindingReadFailure_shouldReturnFail() {
        when(valueOperations.get(anyString())).thenThrow(new RuntimeException("redis down"));
        BatchConfirmUploadRequest request = teamConfirmRequest("files/uuid-redis.txt", "redis.txt");

        BatchUploadConfirmResultVO result = fileUploadService.confirmUpload(request, 1L);

        assertEquals(1, result.getFailCount());
        verify(fileUploadPersistenceService, never()).saveFileItem(any(FileItem.class));
    }

    /** 构造一个 TEAM 空间的单文件确认请求（objectKey / originalName 可指定）。 */
    private BatchConfirmUploadRequest teamConfirmRequest(String objectKey, String originalName) {
        ConfirmUploadRequest item = new ConfirmUploadRequest();
        item.setObjectKey(objectKey);
        item.setOriginalName(originalName);
        item.setFileSize(1024L);
        item.setParentId(100L);
        item.setTeamId(10L);
        item.setSpaceType(uno.acloud.common.FileSpaceType.TEAM);

        BatchConfirmUploadRequest request = new BatchConfirmUploadRequest();
        request.setTeamId(10L);
        request.setSpaceType(uno.acloud.common.FileSpaceType.TEAM);
        request.setFiles(List.of(item));
        return request;
    }

    // ==================== 2.1.3：直传校验前置 + 写失败补偿删除 ====================

    @Test
    void directUpload_saveFails_shouldDeleteWrittenObject() {
        when(fileDomainValidator.validateInputName("cleanup.txt")).thenReturn("cleanup.txt");
        when(defaultProvider.supportsPresignedUpload()).thenReturn(false);
        when(defaultProvider.receiveUpload(anyString(), any(), anyString(), anyString())).thenReturn(16L);
        when(defaultProvider.generateDownloadInfo(anyString(), anyString()))
                .thenReturn(new DownloadInfo("local", "/download/files/uuid-cleanup.txt", "cleanup.txt", true));

        Folder parentFolder = Folder.create();
        parentFolder.setId(100L);
        parentFolder.setTeamId(10L);
        parentFolder.setSpaceType(uno.acloud.common.FileSpaceType.TEAM);
        when(fileDomainValidator.requireFolder(100L)).thenReturn(parentFolder);
        when(fileUploadPersistenceService.saveFileItem(any(FileItem.class)))
                .thenThrow(new RuntimeException("db down"));

        java.io.ByteArrayInputStream inputStream =
                new java.io.ByteArrayInputStream("hello".getBytes());

        assertThrows(RuntimeException.class, () -> fileUploadService.directUpload(
                "cleanup.txt", inputStream, "text/plain", 100L, 1L, 10L, 1, null, 16L));

        // 落库失败必须补偿删除，否则存储里留下无记账的孤儿对象
        verify(defaultProvider).deleteObject(anyString());
    }

    @Test
    void directUpload_streamExceedingLimit_shouldRejectAndCleanup() {
        // 声明 8 字节（不超上限）但实际写 16 字节：应被逐字节计数拦下并清理
        FileUploadService tinyLimitService = new FileUploadService(
                registry, fileUploadPersistenceService, fileDomainValidator,
                filePathResolver, fileAccessGuardService, restClient,
                objectMapper, serviceProperties, usageLedgerMapper, redisTemplate, "", "", 8L);

        when(fileDomainValidator.validateInputName("tiny.txt")).thenReturn("tiny.txt");
        when(defaultProvider.supportsPresignedUpload()).thenReturn(false);
        when(defaultProvider.receiveUpload(anyString(), any(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    java.io.InputStream in = invocation.getArgument(1);
                    byte[] buffer = new byte[4];
                    long total = 0L;
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        total += read;
                    }
                    return total;
                });

        Folder parentFolder = Folder.create();
        parentFolder.setId(100L);
        parentFolder.setTeamId(10L);
        parentFolder.setSpaceType(uno.acloud.common.FileSpaceType.TEAM);
        when(fileDomainValidator.requireFolder(100L)).thenReturn(parentFolder);

        java.io.ByteArrayInputStream inputStream =
                new java.io.ByteArrayInputStream("0123456789abcdef".getBytes());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> tinyLimitService.directUpload(
                        "tiny.txt", inputStream, "text/plain", 100L, 1L, 10L, 1, null, 8L));

        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("文件大小超过限制"));
        verify(defaultProvider).deleteObject(anyString());
    }
}
