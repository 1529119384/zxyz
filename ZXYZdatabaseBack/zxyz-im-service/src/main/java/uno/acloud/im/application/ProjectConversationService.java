package uno.acloud.im.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.im.domain.enums.ConversationMemberStatus;
import uno.acloud.im.domain.enums.ConversationType;
import uno.acloud.im.infrastructure.persistence.entity.ImConversation;
import uno.acloud.im.infrastructure.mapper.ConversationMapper;
import uno.acloud.im.infrastructure.mapper.ImEntityMapper;
import uno.acloud.im.dto.CreateProjectConversationRequest;
import uno.acloud.im.dto.ProjectCreateRequestMessageRequest;
import uno.acloud.im.dto.ProjectCreateRequestReviewResultRequest;
import uno.acloud.im.vo.ProjectConversationVO;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Service
public class ProjectConversationService {

    private final ConversationMapper conversationMapper;
    private final TeamNotificationConversationService teamNotificationConversationService;
    private final ImEntityMapper imEntityMapper;

    public ProjectConversationService(ConversationMapper conversationMapper,
                                      TeamNotificationConversationService teamNotificationConversationService,
                                      ImEntityMapper imEntityMapper) {
        this.conversationMapper = conversationMapper;
        this.teamNotificationConversationService = teamNotificationConversationService;
        this.imEntityMapper = imEntityMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public ProjectConversationVO createOrGet(CreateProjectConversationRequest request) {
        Long projectId = request == null ? null : request.getProjectId();
        Long teamId = request == null ? null : request.getTeamId();
        Long leaderUserId = request == null ? null : request.getLeaderUserId();
        String projectName = normalizeProjectName(request == null ? null : request.getName());
        if (projectId == null || teamId == null || leaderUserId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "projectId、teamId、leaderUserId 不能为空");
        }
        String bizKey = "PROJECT:" + projectId;
        ImConversation existing = conversationMapper.getConversationByBizKey(bizKey);
        if (existing != null) {
            syncProjectConversationName(existing, projectName);
            conversationMapper.upsertConversationMember(existing.getId(), leaderUserId);
            return imEntityMapper.toConversationVO(existing);
        }

        LocalDateTime now = LocalDateTime.now();
        ImConversation conversation = new ImConversation();
        conversation.setType(ConversationType.PROJECT);
        conversation.setTeamId(teamId);
        conversation.setProjectId(projectId);
        conversation.setName(projectName);
        conversation.setBizKey(bizKey);
        conversation.setDirectUserA(null);
        conversation.setDirectUserB(null);
        conversation.setStatus(ConversationMemberStatus.ACTIVE);
        conversation.setReadOnly(Boolean.FALSE);
        conversation.setCreateTime(now);
        conversation.setUpdateTime(now);
        conversationMapper.insertConversation(conversation);
        conversationMapper.upsertConversationMember(conversation.getId(), leaderUserId);
        return imEntityMapper.toConversationVO(conversation);
    }

    @Transactional(rollbackFor = Exception.class)
    public void archive(Long projectId) {
        if (projectId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "projectId 不能为空");
        }
        conversationMapper.archiveProjectConversation(projectId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void appendProjectCreateRequestMessage(ProjectCreateRequestMessageRequest request) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("applicationId", request.getApplicationId());
        payload.put("status", "PENDING");
        payload.put("projectName", request.getProjectName());
        payload.put("description", request.getDescription());
        payload.put("requesterUserId", request.getRequesterUserId());
        payload.put("requesterName", request.getRequesterName());
        payload.put("leaderUserId", request.getLeaderUserId());
        payload.put("leaderName", request.getLeaderName());
        payload.put("storageLimit", request.getStorageLimit());
        payload.put("content", "项目组申请：" + request.getProjectName());
        teamNotificationConversationService.appendProjectCreateRequest(request.getTeamId(), request.getRequesterUserId(), payload);
    }

    @Transactional(rollbackFor = Exception.class)
    public void appendProjectCreateRequestResultMessage(Long applicationId, ProjectCreateRequestReviewResultRequest request) {
        teamNotificationConversationService.updateProjectCreateRequestStatus(
                request == null ? null : request.getTeamId(),
                applicationId,
                request == null ? null : request.getReviewerUserId(),
                request != null && Boolean.TRUE.equals(request.getApproved()),
                request == null ? null : request.getProjectId(),
                request == null ? null : request.getReviewReason()
        );
    }

    private String normalizeProjectName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "项目名称不能为空");
        }
        String normalized = name.trim();
        if (normalized.length() > 80) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "项目名称长度不能超过 80");
        }
        return normalized;
    }

    private void syncProjectConversationName(ImConversation conversation, String projectName) {
        if (!projectName.equals(conversation.getName())) {
            // 项目会话是 IM 的协作投影，幂等重试时用主服务传入的项目名修正历史空值或旧值。
            conversationMapper.updateProjectConversationName(conversation.getId(), projectName);
            conversation.setName(projectName);
        }
    }
}
