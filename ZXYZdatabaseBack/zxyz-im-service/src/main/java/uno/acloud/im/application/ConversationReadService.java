package uno.acloud.im.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.im.domain.model.ImConversationMember;
import uno.acloud.im.infrastructure.mapper.ConversationMapper;
import uno.acloud.im.infrastructure.mapper.ImMessageMapper;
import uno.acloud.im.vo.ConversationReadVO;

import java.util.List;

@Service
public class ConversationReadService {

    private final ConversationService conversationService;
    private final ConversationMapper conversationMapper;
    private final ImMessageMapper imMessageMapper;
    private final ConversationSerialExecutor serialExecutor;

    public ConversationReadService(ConversationService conversationService,
                                   ConversationMapper conversationMapper,
                                   ImMessageMapper imMessageMapper,
                                   ConversationSerialExecutor serialExecutor) {
        this.conversationService = conversationService;
        this.conversationMapper = conversationMapper;
        this.imMessageMapper = imMessageMapper;
        this.serialExecutor = serialExecutor;
    }

    @Transactional(rollbackFor = Exception.class)
    public UpdateReadResult updateReadPosition(Long userId, Long conversationId, Long lastReadMessageId) {
        conversationService.requireConversationMember(conversationId, userId);
        if (lastReadMessageId == null || lastReadMessageId <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "lastReadMessageId 不能为空");
        }
        if (imMessageMapper.countConversationMessage(conversationId, lastReadMessageId) <= 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "阅读位置对应的消息不存在");
        }

        return serialExecutor.executeReadUpdate(
                conversationId,
                () -> doUpdateReadPosition(userId, conversationId, lastReadMessageId)
        );
    }

    protected UpdateReadResult doUpdateReadPosition(Long userId, Long conversationId, Long lastReadMessageId) {
        ImConversationMember member = conversationMapper.getConversationMember(conversationId, userId);
        if (member == null) {
            throw new BusinessException(ErrorCode.NO_PERMISSION, "你无权访问该会话");
        }

        long currentLastRead = member.getLastReadMessageId() == null ? 0L : member.getLastReadMessageId();
        if (lastReadMessageId <= currentLastRead) {
            return new UpdateReadResult(
                    new ConversationReadVO(conversationId, userId, currentLastRead),
                    conversationMapper.listActiveMemberUserIds(conversationId)
            );
        }

        conversationMapper.updateReadState(conversationId, userId, lastReadMessageId);
        return new UpdateReadResult(
                new ConversationReadVO(conversationId, userId, lastReadMessageId),
                conversationMapper.listActiveMemberUserIds(conversationId)
        );
    }

    public record UpdateReadResult(ConversationReadVO readState, List<Long> memberUserIds) {
    }
}
