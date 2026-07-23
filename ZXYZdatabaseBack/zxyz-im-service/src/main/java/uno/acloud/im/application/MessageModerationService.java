package uno.acloud.im.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.TeamPermissionCodes;
import uno.acloud.exception.BusinessException;
import uno.acloud.im.config.MessageModerationProperties;
import uno.acloud.im.domain.enums.ConversationType;
import uno.acloud.im.domain.model.ImConversation;
import uno.acloud.im.domain.model.ImMessage;
import uno.acloud.im.domain.model.TeamMember;
import uno.acloud.im.infrastructure.mapper.ConversationMapper;
import uno.acloud.im.infrastructure.mapper.ImMessageMapper;
import uno.acloud.im.infrastructure.mapper.TeamMapper;
import uno.acloud.im.dto.RecallMessageRequest;
import uno.acloud.im.vo.MessageRecallVO;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static uno.acloud.common.InputNormalizer.optionalText;

@Service
public class MessageModerationService {

    private final ImMessageMapper imMessageMapper;
    private final ConversationMapper conversationMapper;
    private final TeamMapper teamMapper;
    private final ConversationService conversationService;
    private final int recallWindowSeconds;
    private final TeamPermissionService teamPermissionService;

    public MessageModerationService(ImMessageMapper imMessageMapper,
                                    ConversationMapper conversationMapper,
                                    TeamMapper teamMapper,
                                    ConversationService conversationService,
                                    TeamPermissionService teamPermissionService,
                                    MessageModerationProperties messageModerationProperties) {
        this.imMessageMapper = imMessageMapper;
        this.conversationMapper = conversationMapper;
        this.teamMapper = teamMapper;
        this.conversationService = conversationService;
        this.teamPermissionService = teamPermissionService;
        this.recallWindowSeconds = messageModerationProperties.getRecallWindowSeconds();
    }

    @Transactional(rollbackFor = Exception.class)
    public RecallResult recall(Long operatorUserId, Long messageId, RecallMessageRequest request) {
        if (messageId == null || messageId <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "messageId 不能为空");
        }
        ImMessage message = imMessageMapper.getById(messageId);
        if (message == null || !Integer.valueOf(0).equals(message.getStatus())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "消息不存在或已撤回");
        }
        conversationService.requireConversationMember(message.getConversationId(), operatorUserId);
        ImConversation conversation = conversationMapper.getConversationById(message.getConversationId());
        if (conversation == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "会话不存在");
        }

        boolean ownMessage = operatorUserId.equals(message.getSenderUserId());
        boolean managerRecall = canManagerRecall(operatorUserId, conversation);
        if (!ownMessage && !managerRecall) {
            throw new BusinessException(ErrorCode.TEAM_PERMISSION_DENIED, "不能撤回他人消息");
        }
        if (ownMessage && !managerRecall && isRecallExpired(message.getCreateTime())) {
            throw new BusinessException(ErrorCode.TEAM_PERMISSION_DENIED, "消息已超过可撤回时间");
        }

        String reason = request == null ? null : optionalText(request.getReason());
        if (imMessageMapper.recallMessage(messageId, operatorUserId, reason) != 1) {
            throw new BusinessException(ErrorCode.CONCURRENT_OPERATION, "消息状态已变化");
        }
        ImMessage recalled = imMessageMapper.getById(messageId);
        MessageRecallVO recall = new MessageRecallVO(
                messageId,
                message.getConversationId(),
                operatorUserId,
                recalled == null ? LocalDateTime.now() : recalled.getRecallTime(),
                reason
        );
        return new RecallResult(recall, conversationMapper.listActiveMemberUserIds(message.getConversationId()));
    }

    private boolean canManagerRecall(Long operatorUserId, ImConversation conversation) {
        if (!ConversationType.TEAM.equals(conversation.getType()) || conversation.getTeamId() == null) {
            return false;
        }
        TeamMember member = teamMapper.getActiveMember(conversation.getTeamId(), operatorUserId);
        return member != null && teamPermissionService.hasPermission(conversation.getTeamId(), operatorUserId, TeamPermissionCodes.TEAM_MUTE_MANAGE);
    }

    private boolean isRecallExpired(LocalDateTime createTime) {
        if (createTime == null) {
            return true;
        }
        return Duration.between(createTime, LocalDateTime.now()).getSeconds() > recallWindowSeconds;
    }

    public record RecallResult(MessageRecallVO recall, List<Long> memberUserIds) {
    }
}
