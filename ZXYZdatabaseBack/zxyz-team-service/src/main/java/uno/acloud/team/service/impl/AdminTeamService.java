package uno.acloud.team.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.TeamErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.team.dto.system.BroadcastSystemMessageRequest;
import uno.acloud.team.dto.system.ScheduledEmailBatchRequest;
import uno.acloud.team.dto.team.UpdateTeamQuotaRequest;
import uno.acloud.team.entity.Team;
import uno.acloud.team.entity.TeamQuota;
import uno.acloud.team.infrastructure.client.EmailServiceClient;
import uno.acloud.team.infrastructure.client.FileServiceClient;
import uno.acloud.team.infrastructure.client.ImSystemNotificationClient;
import uno.acloud.team.infrastructure.client.ProjectServiceClient;
import uno.acloud.team.infrastructure.client.UserServiceClient;
import uno.acloud.team.mapper.TeamMapper;
import uno.acloud.team.mapper.TeamQuotaMapper;
import uno.acloud.team.service.AdminTeamPort;
import uno.acloud.team.vo.team.AdminTeamOverviewVO;

import uno.acloud.dto.UserInfoDTO;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static uno.acloud.common.InputNormalizer.requireText;

@Service
@Slf4j
public class AdminTeamService implements AdminTeamPort {

    private static final String TEAM_QUOTA_NOTIFICATION_TYPE = "TEAM_QUOTA_UPDATED";
    private static final String TEAM_QUOTA_NOTIFICATION_BUSINESS = "TEAM_QUOTA_UPDATED";
    private static final String GLOBAL_BROADCAST_TYPE = "SYSTEM_ADMIN_BROADCAST";
    private static final String GLOBAL_BROADCAST_BUSINESS = "SYSTEM_ADMIN_BROADCAST";

    private final TeamMapper teamMapper;
    private final TeamQuotaMapper teamQuotaMapper;
    private final UserServiceClient userServiceClient;
    private final FileServiceClient fileServiceClient;
    private final ProjectServiceClient projectServiceClient;
    private final ImSystemNotificationClient imSystemNotificationClient;
    private final EmailServiceClient emailServiceClient;
    private AdminTeamService self;

    public AdminTeamService(TeamMapper teamMapper,
                            TeamQuotaMapper teamQuotaMapper,
                            UserServiceClient userServiceClient,
                            FileServiceClient fileServiceClient,
                            ProjectServiceClient projectServiceClient,
                            ImSystemNotificationClient imSystemNotificationClient,
                            EmailServiceClient emailServiceClient,
                            @Lazy AdminTeamService self) {
        this.teamMapper = teamMapper;
        this.teamQuotaMapper = teamQuotaMapper;
        this.userServiceClient = userServiceClient;
        this.fileServiceClient = fileServiceClient;
        this.projectServiceClient = projectServiceClient;
        this.imSystemNotificationClient = imSystemNotificationClient;
        this.emailServiceClient = emailServiceClient;
        this.self = self;
    }

    @Override
    public List<AdminTeamOverviewVO> listTeams() {
        List<AdminTeamOverviewVO> overviews = teamMapper.listAdminTeamOverviews();
        if (overviews.isEmpty()) {
            return overviews;
        }
        // Batch-fetch owner usernames (avoid N+1 HTTP calls)
        List<Long> ownerUserIds = overviews.stream()
                .map(AdminTeamOverviewVO::getOwnerUserId)
                .distinct()
                .toList();
        Map<Long, UserInfoDTO> ownerMap = userServiceClient.listByIds(ownerUserIds).stream()
                .collect(Collectors.toMap(UserInfoDTO::getId, Function.identity()));
        // Batch-fetch team storage usage (avoid N+1 HTTP calls)
        List<Long> teamIds = overviews.stream()
                .map(AdminTeamOverviewVO::getId)
                .toList();
        Map<Long, Long> storageMap = fileServiceClient.listTeamStorageUsageByTeamIds(teamIds);
        // Populate VOs
        for (AdminTeamOverviewVO vo : overviews) {
            UserInfoDTO owner = ownerMap.get(vo.getOwnerUserId());
            if (owner != null) {
                vo.setOwnerUsername(owner.getUsername());
            }
            vo.setUsedStorage(storageMap.getOrDefault(vo.getId(), 0L));
        }
        return overviews;
    }

    @Override
    public AdminTeamOverviewVO updateTeamQuota(Long teamId, UpdateTeamQuotaRequest request) {
        // Phase 1: Pre-transaction validation + HTTP calls
        Team team = teamMapper.selectById(teamId);
        if (team == null || !Integer.valueOf(0).equals(team.getStatus())) {
            throw new BusinessException(TeamErrorCode.TEAM_NOT_FOUND.getCode(), "团队不存在");
        }

        int memberLimit = normalizePositive(request == null ? null : request.getMemberLimit(), "团队人数上限必须大于 0");
        long storageLimit = normalizePositiveLong(request == null ? null : request.getStorageLimit(), "团队空间上限必须大于 0");
        int currentMemberCount = teamMapper.countOccupiedMembers(teamId);
        if (memberLimit < currentMemberCount) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "团队人数上限不能小于当前成员数");
        }
        // HTTP call outside transaction to avoid holding DB connection during remote I/O
        long usedStorage = fileServiceClient.sumActiveFileSize(null, teamId, 2, null);
        if (storageLimit < usedStorage) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "团队空间上限不能小于当前已使用空间");
        }

        // CV-3: 校验团队配额不小于项目配额总和
        long projectQuotaSum = projectServiceClient.sumProjectQuota(teamId);
        if (projectQuotaSum > 0 && storageLimit < projectQuotaSum) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "团队存储配额不能小于项目配额总和: " + projectQuotaSum);
        }

        // Phase 2: DB transaction
        self.doUpdateTeamQuota(teamId, memberLimit, storageLimit);

        // Phase 3: Post-transaction HTTP calls
        imSystemNotificationClient.sendBatch(
                teamMapper.listAdminUserIds(teamId),
                TEAM_QUOTA_NOTIFICATION_TYPE,
                "团队配额已更新",
                buildQuotaUpdateContent(team.getName(), memberLimit, storageLimit),
                TEAM_QUOTA_NOTIFICATION_BUSINESS,
                teamId,
                teamId
        );

        return listTeams().stream()
                .filter(item -> item.getId().equals(teamId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.SYSTEM_ERROR, "团队信息刷新失败"));
    }

    @Transactional(rollbackFor = Exception.class)
    public void doUpdateTeamQuota(Long teamId, int memberLimit, long storageLimit) {
        TeamQuota quota = new TeamQuota();
        quota.setTeamId(teamId);
        quota.setMemberLimit(memberLimit);
        quota.setStorageLimit(storageLimit);
        quota.setCreateTime(LocalDateTime.now());
        quota.setUpdateTime(LocalDateTime.now());
        teamQuotaMapper.upsertQuota(quota);
    }

    @Override
    public void broadcastSystemMessage(BroadcastSystemMessageRequest request) {
        String title = requireText(request == null ? null : request.getTitle(), "系统消息标题不能为空", 120, "内容长度不能超过 120");
        String content = requireText(request == null ? null : request.getContent(), "系统消息内容不能为空", 5000, "内容长度不能超过 5000");
        imSystemNotificationClient.sendBatch(
                userServiceClient.getAllUserIds(),
                GLOBAL_BROADCAST_TYPE,
                title,
                content,
                GLOBAL_BROADCAST_BUSINESS,
                null,
                null
        );
        sendBroadcastEmail(title, content);
    }

    @Override
    public void scheduleSystemEmailBatch(ScheduledEmailBatchRequest request) {
        String subject = requireText(request == null ? null : request.getSubject(), "邮件主题不能为空", 120, "邮件主题不能超过 120");
        String contentHtml = requireText(request == null ? null : request.getContentHtml(), "邮件内容不能为空", 5000, "邮件内容不能超过 5000");
        LocalDateTime scheduledTime = request == null ? null : request.getScheduledTime();
        if (scheduledTime == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "发送时间不能为空");
        }
        List<String> recipients = userServiceClient.getVerifiedEmails();
        if (recipients.isEmpty()) {
            return;
        }
        try {
            emailServiceClient.scheduleBatch(recipients, subject, contentHtml, scheduledTime, "ADMIN_SCHEDULED_EMAIL", null);
        } catch (Exception e) {
            log.warn("调度批量邮件失败: subject={}, recipientCount={}", subject, recipients.size(), e);
        }
    }

    private void sendBroadcastEmail(String title, String content) {
        List<String> recipients = userServiceClient.getVerifiedEmails();
        if (recipients.isEmpty()) {
            return;
        }
        try {
            emailServiceClient.sendBatchByTemplate(
                    recipients,
                    "SYSTEM_MESSAGE",
                    java.util.Map.of("title", title, "content", content),
                    GLOBAL_BROADCAST_BUSINESS,
                    null
            );
        } catch (Exception e) {
            log.warn("全局系统消息邮件投递失败，已保留站内消息：recipientCount={}", recipients.size(), e);
        }
    }

    private String buildQuotaUpdateContent(String teamName, int memberLimit, long storageLimit) {
        return "团队【" + teamName + "】的成员上限已调整为 " + memberLimit
                + " 人，空间上限已调整为 " + formatStorageLimit(storageLimit) + "。";
    }

    private String formatStorageLimit(long storageLimit) {
        double gbValue = storageLimit / 1024d / 1024d / 1024d;
        if (gbValue >= 1024d) {
            return String.format("%.2f TB", gbValue / 1024d);
        }
        return String.format("%.2f GB", gbValue);
    }

    private int normalizePositive(Integer value, String message) {
        if (value == null || value <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, message);
        }
        return value;
    }

    private long normalizePositiveLong(Long value, String message) {
        if (value == null || value <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, message);
        }
        return value;
    }

    // Package-private setter for unit testing without Spring proxy
    void setSelf(AdminTeamService self) {
        this.self = self;
    }
}
