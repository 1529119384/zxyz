package uno.acloud.im.application;

import org.springframework.stereotype.Service;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.im.domain.model.ImConversation;
import uno.acloud.im.infrastructure.mapper.ConversationMapper;
import uno.acloud.im.infrastructure.mapper.TeamManagementMapper;

@Service
public class TeamMutePolicyService {

    private final ConversationMapper conversationMapper;
    private final TeamManagementMapper managementMapper;

    public TeamMutePolicyService(ConversationMapper conversationMapper, TeamManagementMapper managementMapper) {
        this.conversationMapper = conversationMapper;
        this.managementMapper = managementMapper;
    }

    public void requireCanSend(Long userId, Long conversationId) {
        ImConversation conversation = conversationMapper.getConversationById(conversationId);
        if (conversation == null || conversation.getTeamId() == null) {
            return;
        }
        if (managementMapper.getActiveMute(conversation.getTeamId(), userId) != null) {
            throw new BusinessException(ErrorCode.TEAM_PERMISSION_DENIED, "你已被团队禁言，暂时无法发送消息");
        }
    }
}
