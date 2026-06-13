package uno.acloud.im.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.im.domain.enums.ConversationMemberStatus;
import uno.acloud.im.domain.enums.ConversationType;
import uno.acloud.im.domain.model.ImConversation;
import uno.acloud.im.infrastructure.mapper.ConversationMapper;
import uno.acloud.im.vo.ConversationSummaryVO;

import java.time.LocalDateTime;

@Service
public class DirectConversationService {

    private final TeamService teamService;
    private final ConversationMapper conversationMapper;
    private final ConversationLockManager lockManager;

    public DirectConversationService(TeamService teamService,
                                     ConversationMapper conversationMapper,
                                     ConversationLockManager lockManager) {
        this.teamService = teamService;
        this.conversationMapper = conversationMapper;
        this.lockManager = lockManager;
    }

    @Transactional(rollbackFor = Exception.class)
    public ConversationSummaryVO createOrGet(Long userId, Long teamId, Long targetUserId) {
        if (teamId == null || teamId <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "teamId 不能为空");
        }
        if (targetUserId == null || targetUserId <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "targetUserId 不能为空");
        }
        if (userId.equals(targetUserId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不能和自己发起私聊");
        }

        teamService.requireActiveMember(teamId, userId);
        teamService.requireActiveMember(teamId, targetUserId);

        Long directUserA = Math.min(userId, targetUserId);
        Long directUserB = Math.max(userId, targetUserId);
        String bizKey = buildBizKey(teamId, directUserA, directUserB);

        return lockManager.withLock(
                "direct-conversation:" + bizKey,
                () -> doCreateOrGet(userId, teamId, directUserA, directUserB, bizKey)
        );
    }

    protected ConversationSummaryVO doCreateOrGet(Long userId,
                                                  Long teamId,
                                                  Long directUserA,
                                                  Long directUserB,
                                                  String bizKey) {
        ImConversation existing = conversationMapper.getConversationByBizKey(bizKey);
        if (existing == null) {
            LocalDateTime now = LocalDateTime.now();
            ImConversation conversation = new ImConversation();
            conversation.setType(ConversationType.DIRECT);
            conversation.setTeamId(teamId);
            conversation.setBizKey(bizKey);
            conversation.setDirectUserA(directUserA);
            conversation.setDirectUserB(directUserB);
            conversation.setStatus(ConversationMemberStatus.ACTIVE);
            conversation.setCreateTime(now);
            conversation.setUpdateTime(now);
            conversationMapper.insertConversation(conversation);
            conversationMapper.upsertConversationMember(conversation.getId(), directUserA);
            conversationMapper.upsertConversationMember(conversation.getId(), directUserB);
            existing = conversation;
        }

        ConversationSummaryVO summary = conversationMapper.getConversationSummary(existing.getId(), userId);
        if (summary == null) {
            throw new BusinessException(ErrorCode.NO_PERMISSION, "你无权访问该私聊会话");
        }
        return summary;
    }

    private String buildBizKey(Long teamId, Long directUserA, Long directUserB) {
        return "DIRECT:" + teamId + ":" + directUserA + ":" + directUserB;
    }
}
