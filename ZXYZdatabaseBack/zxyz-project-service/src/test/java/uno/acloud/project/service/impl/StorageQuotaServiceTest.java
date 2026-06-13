package uno.acloud.project.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.FileSpaceType;
import uno.acloud.exception.BusinessException;
import uno.acloud.project.config.ServiceProperties;
import uno.acloud.project.entity.ProjectQuota;
import uno.acloud.project.entity.UserQuota;
import uno.acloud.project.mapper.ProjectQuotaMapper;
import uno.acloud.project.vo.StorageUsageVO;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StorageQuotaServiceTest {

    @Mock
    private FileServiceClient fileServiceClient;

    @Mock
    private UserQuotaClient userQuotaClient;

    @Mock
    private ProjectQuotaMapper projectQuotaMapper;

    @Mock
    private TeamServiceClient teamServiceClient;

    @Mock
    private StorageQuotaCacheService cacheService;

    private StorageQuotaService storageQuotaService;

    private static final long DEFAULT_PERSONAL_LIMIT = 10737418240L; // 10GB

    @BeforeEach
    void setUp() {
        ServiceProperties props = new ServiceProperties();
        props.getStorage().setPersonalDefaultLimit(DEFAULT_PERSONAL_LIMIT);
        storageQuotaService = new StorageQuotaService(
                fileServiceClient, userQuotaClient, projectQuotaMapper,
                teamServiceClient, cacheService, props);
    }

    // ==================== checkUploadQuota — sufficient quota ====================

    @Test
    void checkUploadQuota_sufficientQuota_shouldPass() {
        Long userId = 1L;
        // Personal space: no team, no project
        // User is not system admin, no team — now resolved via cacheService
        when(cacheService.getSystemRolesByUserId(userId)).thenReturn(List.of());
        when(cacheService.listUserTeamIds(userId)).thenReturn(List.of());
        // Used storage = 500 (cacheService is called with normalized params: userId=1, teamId=null, spaceType=1, projectId=null)
        when(cacheService.sumActiveFileSize(eq(userId), isNull(), eq(FileSpaceType.PERSONAL), isNull()))
                .thenReturn(500L);
        // No user quota set → default 10GB
        when(userQuotaClient.getByUserId(userId)).thenReturn(null);

        // Should not throw
        assertDoesNotThrow(() ->
                storageQuotaService.checkUploadQuota(userId, null, FileSpaceType.PERSONAL, null, 1000L));
    }

    // ==================== checkUploadQuota — exceeding quota ====================

    @Test
    void checkUploadQuota_exceedingQuota_shouldThrow() {
        Long userId = 1L;
        // User is not system admin, no team — now resolved via cacheService
        when(cacheService.getSystemRolesByUserId(userId)).thenReturn(List.of());
        when(cacheService.listUserTeamIds(userId)).thenReturn(List.of());
        // Used = 500
        when(cacheService.sumActiveFileSize(eq(userId), isNull(), eq(FileSpaceType.PERSONAL), isNull()))
                .thenReturn(500L);
        // Limit = 1000
        UserQuota quota = new UserQuota();
        quota.setUserId(userId);
        quota.setStorageLimit(1000L);
        when(userQuotaClient.getByUserId(userId)).thenReturn(quota);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                storageQuotaService.checkUploadQuota(userId, null, FileSpaceType.PERSONAL, null, 1000L));
        assertEquals(ErrorCode.FILE_STATE_INVALID, ex.getErrorCode());
    }

    // ==================== checkUploadQuota — unlimited quota ====================

    @Test
    void checkUploadQuota_unlimitedQuota_shouldPass() {
        // Team space with null storage limit = unlimited
        Long teamId = 10L;
        // sumUsedStorage calls cacheService with userId=1, teamId=10, spaceType=2
        when(cacheService.sumActiveFileSize(eq(1L), eq(teamId), eq(FileSpaceType.TEAM), isNull()))
                .thenReturn(500L);
        when(cacheService.getTeamStorageLimit(teamId)).thenReturn(null);

        assertDoesNotThrow(() ->
                storageQuotaService.checkUploadQuota(1L, teamId, FileSpaceType.TEAM, null, 999999L));
    }

    // ==================== checkUploadQuota — zero upload bytes ====================

    @Test
    void checkUploadQuota_zeroBytes_shouldReturnImmediately() {
        assertDoesNotThrow(() ->
                storageQuotaService.checkUploadQuota(1L, null, FileSpaceType.PERSONAL, null, 0L));

        // No remote calls should be made
        verifyNoInteractions(cacheService, teamServiceClient, userQuotaClient, projectQuotaMapper);
    }

    // ==================== getUsage — personal space ====================

    @Test
    void getUsage_personalSpace_shouldReturnCorrectUsage() {
        Long userId = 1L;
        when(cacheService.sumActiveFileSize(eq(userId), isNull(), eq(FileSpaceType.PERSONAL), isNull()))
                .thenReturn(500L);
        when(userQuotaClient.getByUserId(userId)).thenReturn(null); // → default 10GB

        StorageUsageVO usage = storageQuotaService.getUsage(userId, FileSpaceType.PERSONAL, null, null);

        assertEquals(FileSpaceType.PERSONAL, usage.getSpaceType());
        assertEquals(500L, usage.getUsedStorage());
        assertEquals(DEFAULT_PERSONAL_LIMIT, usage.getStorageLimit());
        assertFalse(usage.getUnlimited());
        assertEquals(DEFAULT_PERSONAL_LIMIT - 500L, usage.getRemainingStorage());
    }

    // ==================== getUsage — team space ====================

    @Test
    void getUsage_teamSpace_shouldReturnCorrectUsage() {
        Long teamId = 10L;
        long teamLimit = 1024L * 1024L * 1024L * 50L; // 50GB
        // sumUsedStorage: userId=1, teamId=10, spaceType=2
        when(cacheService.sumActiveFileSize(eq(1L), eq(teamId), eq(FileSpaceType.TEAM), isNull()))
                .thenReturn(1000L);
        when(cacheService.getTeamStorageLimit(teamId)).thenReturn(teamLimit);

        StorageUsageVO usage = storageQuotaService.getUsage(1L, FileSpaceType.TEAM, teamId, null);

        assertEquals(FileSpaceType.TEAM, usage.getSpaceType());
        assertEquals(teamId, usage.getTeamId());
        assertEquals(1000L, usage.getUsedStorage());
        assertEquals(teamLimit, usage.getStorageLimit());
        assertFalse(usage.getUnlimited());
        assertEquals(teamLimit - 1000L, usage.getRemainingStorage());
    }

    // ==================== getUsage — project space ====================

    @Test
    void getUsage_projectSpace_shouldReturnCorrectUsage() {
        Long projectId = 20L;
        long projectLimit = 5000L;
        // sumUsedStorage: userId=1, teamId=null, spaceType=3, projectId=20
        when(cacheService.sumActiveFileSize(eq(1L), isNull(), eq(FileSpaceType.PROJECT), eq(projectId)))
                .thenReturn(300L);
        ProjectQuota quota = new ProjectQuota();
        quota.setProjectId(projectId);
        quota.setStorageLimit(projectLimit);
        when(projectQuotaMapper.getByProjectId(projectId)).thenReturn(quota);

        StorageUsageVO usage = storageQuotaService.getUsage(1L, FileSpaceType.PROJECT, null, projectId);

        assertEquals(FileSpaceType.PROJECT, usage.getSpaceType());
        assertEquals(projectId, usage.getProjectId());
        assertEquals(300L, usage.getUsedStorage());
        assertEquals(projectLimit, usage.getStorageLimit());
        assertFalse(usage.getUnlimited());
    }
}
