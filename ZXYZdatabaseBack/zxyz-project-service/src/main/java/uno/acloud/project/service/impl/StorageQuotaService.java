package uno.acloud.project.service.impl;

import org.springframework.lang.Nullable;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.FileSpaceType;
import uno.acloud.common.SystemRoleCodes;
import uno.acloud.project.config.ServiceProperties;
import uno.acloud.project.entity.ProjectQuota;
import uno.acloud.project.entity.UserQuota;
import uno.acloud.exception.BusinessException;
import uno.acloud.project.mapper.ProjectQuotaMapper;
import uno.acloud.project.service.StorageQuotaPort;
import uno.acloud.project.vo.StorageUsageVO;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
public class StorageQuotaService implements StorageQuotaPort {

    private final ThreadPoolTaskExecutor quotaExecutor;

    private final FileServiceClient fileServiceClient;
    private final UserQuotaClient userQuotaClient;
    private final ProjectQuotaMapper projectQuotaMapper;
    private final TeamServiceClient teamServiceClient;
    private final StorageQuotaCacheService cacheService;
    private final long defaultPersonalStorageLimit;

    public StorageQuotaService(FileServiceClient fileServiceClient,
                               UserQuotaClient userQuotaClient,
                               ProjectQuotaMapper projectQuotaMapper,
                               TeamServiceClient teamServiceClient,
                               StorageQuotaCacheService cacheService,
                               ServiceProperties serviceProperties,
                               ThreadPoolTaskExecutor quotaExecutor) {
        this.fileServiceClient = fileServiceClient;
        this.userQuotaClient = userQuotaClient;
        this.projectQuotaMapper = projectQuotaMapper;
        this.teamServiceClient = teamServiceClient;
        this.cacheService = cacheService;
        this.quotaExecutor = quotaExecutor;
        this.defaultPersonalStorageLimit = serviceProperties.getStorage().getPersonalDefaultLimit();
    }

    @Override
    public StorageUsageVO getUsage(Long userId, Integer spaceType, Long teamId, Long projectId) {
        return getUsageWithContext(userId, spaceType, teamId, projectId, null);
    }

    @Override
    public long sumUsedStorage(Long userId, Long teamId, Integer spaceType, Long projectId) {
        long teamFileStorage = cacheService.sumActiveFileSize(userId, teamId, spaceType, projectId);
        if (!FileSpaceType.isTeam(spaceType) || teamId == null) {
            return teamFileStorage;
        }
        return teamFileStorage + sumMemberPersonalStorage(teamId);
    }

    /**
     * 计算团队成员（不含系统管理员）在个人空间的用量总和。
     */
    private long sumMemberPersonalStorage(Long teamId) {
        List<Long> memberUserIds = cacheService.listTeamMemberUserIds(teamId);
        if (memberUserIds.isEmpty()) {
            return 0;
        }
        Set<Long> adminUserIds = new HashSet<>(cacheService.listSystemAdminUserIds());
        List<Long> nonAdminUserIds = memberUserIds.stream()
                .filter(uid -> !adminUserIds.contains(uid))
                .collect(Collectors.toList());
        if (nonAdminUserIds.isEmpty()) {
            return 0;
        }
        return cacheService.sumPersonalStorageByUsers(nonAdminUserIds);
    }

    private boolean isSystemAdmin(Long userId) {
        if (userId == null) {
            return false;
        }
        return cacheService.getSystemRolesByUserId(userId)
                .contains(SystemRoleCodes.SYSTEM_ADMIN);
    }

    @Override
    public Long checkUploadQuota(Long userId, Long teamId, Integer spaceType, Long projectId, long uploadBytes) {
        if (uploadBytes <= 0) {
            return null;
        }
        Integer normalizedSpaceType = FileSpaceType.normalize(spaceType, teamId, projectId);

        // 为个人空间预解析团队上下文，避免后续重复远程调用
        PersonalLimitContext personalCtx = FileSpaceType.isPersonal(normalizedSpaceType)
                ? resolvePersonalLimitContext(userId) : null;

        StorageUsageVO usage = getUsageWithContext(userId, normalizedSpaceType, teamId, projectId, personalCtx);
        if (!Boolean.TRUE.equals(usage.getUnlimited()) && usage.getUsedStorage() + uploadBytes > usage.getStorageLimit()) {
            throw new BusinessException(ErrorCode.FILE_STATE_INVALID, "上传超过当前空间配额");
        }

        // 项目空间可设置为无限，但仍不能突破团队总空间上限。
        if (FileSpaceType.isProject(normalizedSpaceType) && teamId != null) {
            StorageUsageVO teamUsage = getUsage(userId, FileSpaceType.TEAM, teamId, null);
            if (!Boolean.TRUE.equals(teamUsage.getUnlimited()) && teamUsage.getUsedStorage() + uploadBytes > teamUsage.getStorageLimit()) {
                throw new BusinessException(ErrorCode.FILE_STATE_INVALID, "上传超过团队空间配额");
            }
        }

        // 个人空间上传：校验团队分配的成员个人存储上限和团队总配额
        if (FileSpaceType.isPersonal(normalizedSpaceType)) {
            checkPersonalUploadUnderTeamLimit(userId, uploadBytes, personalCtx);
        }

        // 返回该作用域的有效上限（null=不限制），供 file-service 预检阶段写入配额台账。
        return usage.getUnlimited() ? null : usage.getStorageLimit();
    }

    /**
     * 校验个人空间上传是否超过团队分配的成员个人存储上限和团队总配额。
     * 接收预解析的团队上下文以避免重复远程调用。
     */
    private void checkPersonalUploadUnderTeamLimit(Long userId, long uploadBytes, PersonalLimitContext ctx) {
        if (ctx.systemAdmin()) {
            return;
        }
        if (ctx.teamId() == null) {
            return;
        }

        // 校验团队总配额（含所有成员的个人空间）
        StorageUsageVO teamUsage = getUsage(userId, FileSpaceType.TEAM, ctx.teamId(), null);
        if (!Boolean.TRUE.equals(teamUsage.getUnlimited()) && teamUsage.getUsedStorage() + uploadBytes > teamUsage.getStorageLimit()) {
            throw new BusinessException(ErrorCode.FILE_STATE_INVALID, "上传超过团队空间配额");
        }

        // 校验成员个人存储上限
        if (ctx.teamMemberPersonalLimit() == null) {
            return;
        }
        long memberPersonalUsed = cacheService.sumPersonalStorageByUsers(List.of(userId));
        if (memberPersonalUsed + uploadBytes > ctx.teamMemberPersonalLimit()) {
            throw new BusinessException(ErrorCode.FILE_STATE_INVALID, "上传超过团队分配的个人空间上限");
        }
    }

    /**
     * 预解析个人空间的团队上下文信息，后续多个校验步骤共享使用。
     * 对于多团队用户，取各团队分配的成员个人存储上限中的最大值（null 视为无限制）。
     */
    private PersonalLimitContext resolvePersonalLimitContext(Long userId) {
        // C6: Pre-warm cache — fetch system roles and user team IDs in parallel
        CompletableFuture<List<String>> rolesFuture = CompletableFuture.supplyAsync(
                () -> cacheService.getSystemRolesByUserId(userId), quotaExecutor);
        CompletableFuture<List<Long>> teamIdsFuture = CompletableFuture.supplyAsync(
                () -> cacheService.listUserTeamIds(userId), quotaExecutor);
        CompletableFuture.allOf(rolesFuture, teamIdsFuture).join();

        if (rolesFuture.join().contains(SystemRoleCodes.SYSTEM_ADMIN)) {
            return new PersonalLimitContext(true, null, null);
        }
        List<Long> userTeamIds = teamIdsFuture.join();
        if (userTeamIds.isEmpty()) {
            return new PersonalLimitContext(false, null, null);
        }
        // 遍历用户所属的所有团队，取最宽松的成员个人存储上限
        Long bestTeamId = userTeamIds.get(0);
        Long bestLimit = null; // null = unlimited
        boolean foundUnlimited = false;
        for (Long teamId : userTeamIds) {
            Long limit = teamServiceClient.getMemberPersonalStorageLimit(teamId, userId);
            if (limit == null) {
                // 该团队未设置上限（无限制），无需继续查询
                bestTeamId = teamId;
                foundUnlimited = true;
                break;
            }
            if (bestLimit == null || limit > bestLimit) {
                bestLimit = limit;
                bestTeamId = teamId;
            }
        }
        Long teamMemberLimit = foundUnlimited ? null : bestLimit;
        return new PersonalLimitContext(false, bestTeamId, teamMemberLimit);
    }

    private StorageUsageVO getUsageWithContext(Long userId, Integer spaceType, Long teamId, Long projectId,
                                               PersonalLimitContext personalCtx) {
        Integer normalizedSpaceType = FileSpaceType.normalize(spaceType, teamId, projectId);

        // 仅在无预解析上下文时使用 VO 缓存（getUsage 正常路径）
        if (personalCtx == null) {
            StorageUsageVO cached = cacheService.getUsageVO(userId, normalizedSpaceType, teamId, projectId);
            if (cached != null) {
                return cached;
            }
        }

        Long usedStorage = sumUsedStorage(userId, teamId, normalizedSpaceType, projectId);
        Long storageLimit = resolveStorageLimitWithContext(userId, teamId, normalizedSpaceType, projectId, personalCtx);
        boolean unlimited = storageLimit == null;
        Long remainingStorage = unlimited ? null : Math.max(0, storageLimit - usedStorage);
        StorageUsageVO vo = new StorageUsageVO(normalizedSpaceType, teamId, projectId, usedStorage, storageLimit, remainingStorage, unlimited);

        if (personalCtx == null) {
            cacheService.putUsageVO(userId, normalizedSpaceType, teamId, projectId, vo);
        }

        return vo;
    }

    @Nullable
    private Long resolveStorageLimitWithContext(Long userId, Long teamId, Integer spaceType, Long projectId,
                                                PersonalLimitContext personalCtx) {
        if (FileSpaceType.isProject(spaceType)) {
            ProjectQuota quota = projectQuotaMapper.getByProjectId(projectId);
            return quota == null ? null : quota.getStorageLimit();
        }
        if (FileSpaceType.isTeam(spaceType)) {
            return cacheService.getTeamStorageLimit(teamId);
        }
        // Personal space: effective limit = min(user_quota, team member personal_storage_limit)
        Long personalLimit = resolvePersonalStorageLimit(userId);
        if (personalCtx != null) {
            // 使用预解析的上下文，避免重复远程调用
            Long teamMemberLimit = personalCtx.teamMemberPersonalLimit();
            if (teamMemberLimit == null) {
                return personalLimit;
            }
            return Math.min(personalLimit, teamMemberLimit);
        }
        return resolveTeamMemberPersonalLimit(userId)
                .map(limit -> Math.min(personalLimit, limit))
                .orElse(personalLimit);
    }

    private Long resolvePersonalStorageLimit(Long userId) {
        UserQuota quota = userQuotaClient.getByUserId(userId);
        return quota == null || quota.getStorageLimit() == null ? defaultPersonalStorageLimit : quota.getStorageLimit();
    }

    /**
     * 获取团队管理员分配的成员个人存储上限，非团队成员或无限制时返回 {@link Optional#empty()}。
     * 对于多团队用户，取各团队上限中的最大值（empty 视为无限制）。
     */
    private Optional<Long> resolveTeamMemberPersonalLimit(Long userId) {
        if (isSystemAdmin(userId)) {
            return Optional.empty();
        }
        List<Long> userTeamIds = cacheService.listUserTeamIds(userId);
        if (userTeamIds.isEmpty()) {
            return Optional.empty();
        }
        Long bestLimit = null; // null = unlimited
        for (Long teamId : userTeamIds) {
            Long limit = teamServiceClient.getMemberPersonalStorageLimit(teamId, userId);
            if (limit == null) {
                // 该团队未设置上限，视为无限制
                return Optional.empty();
            }
            if (bestLimit == null || limit > bestLimit) {
                bestLimit = limit;
            }
        }
        return Optional.ofNullable(bestLimit);
    }

    private record PersonalLimitContext(boolean systemAdmin, Long teamId, Long teamMemberPersonalLimit) {}
}
