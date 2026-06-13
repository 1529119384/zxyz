package uno.acloud.im.application;

import uno.acloud.im.vo.ConversationReadVO;
import uno.acloud.im.vo.ImMessageVO;
import uno.acloud.im.vo.MessageRecallVO;

import java.util.Collection;

public interface ImRealtimePushService {

    void pushMessageReceived(Collection<Long> userIds, ImMessageVO message);

    void pushReadUpdated(Collection<Long> userIds, ConversationReadVO readState);

    void pushMessageRecalled(Collection<Long> userIds, MessageRecallVO recall);
}
