package uno.acloud.im.application;

import org.springframework.stereotype.Service;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.TeamErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.im.domain.model.ImConversation;

import uno.acloud.im.infrastructure.mapper.ConversationMapper;
import uno.acloud.im.infrastructure.mapper.TeamMapper;
import uno.acloud.im.vo.ConversationSummaryVO;
import uno.acloud.im.vo.TeamConversationVO;

import java.util.List;

@Service
public class ConversationService {

    private final ConversationMapper conversationMapper;
    private final TeamMapper teamMapper;

    public ConversationService(ConversationMapper conversationMapper, TeamMapper teamMapper) {
        this.conversationMapper = conversationMapper;
        this.teamMapper = teamMapper;
    }

    public List<ConversationSummaryVO> listMyConversations(Long userId, Long teamId) {
        return conversationMapper.listMyConversations(userId, teamId);
    }

    public TeamConversationVO getTeamConversation(Long userId, Long teamId) {
        TeamConversationVO conversation = conversationMapper.getTeamConversation(teamId, userId);
        if (conversation == null) {
            throw new BusinessException(TeamErrorCode.TEAM_NOT_FOUND.getCode(), "团队不存在或你不在该团队中");
        }
        return conversation;
    }

    public void requireConversationMember(Long conversationId, Long userId) {
        if (conversationId == null || conversationId <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "会话 ID 不能为空");
        }
        // 合并查询：一次性验证会话存在性 + 成员身份（JOIN im_conversation + im_conversation_member）
        ImConversation conversation = conversationMapper.getConversationWithActiveMember(conversationId, userId);
        if (conversation == null) {
            throw new BusinessException(ErrorCode.NO_PERMISSION, "你无权访问该会话");
        }
        // 仅当会话关联团队时，额外校验团队成员身份（team_member 为独立表，无法合并）
        if (conversation.getTeamId() != null && teamMapper.getActiveMember(conversation.getTeamId(), userId) == null) {
            throw new BusinessException(ErrorCode.NO_PERMISSION, "你已不在该团队中");
        }
    }

    public ConversationSummaryVO getConversationSummary(Long userId, Long conversationId) {
        requireConversationMember(conversationId, userId);
        ConversationSummaryVO summary = conversationMapper.getConversationSummary(conversationId, userId);
        if (summary == null) {
            throw new BusinessException(ErrorCode.NO_PERMISSION, "你无权访问该会话");
        }
        return summary;
    }

    public void requireWritableConversation(Long conversationId) {
        ImConversation conversation = conversationMapper.getConversationById(conversationId);
        if (conversation != null && Boolean.TRUE.equals(conversation.getReadOnly())) {
            throw new BusinessException(TeamErrorCode.TEAM_PERMISSION_DENIED.getCode(), "项目群聊已归档，只允许查看历史消息");
        }
    }
}
