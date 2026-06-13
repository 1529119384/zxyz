package uno.acloud.im.application;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;

import java.util.ArrayList;
import java.util.List;

@Service
public class ImCommandDispatcher {

    private static final String SEND_TEXT = "SEND_TEXT";
    private static final String SEND_FILE_CARD = "SEND_FILE_CARD";

    private final ImMessageService imMessageService;
    private final FileCardMessageService fileCardMessageService;
    private final ImRealtimePushService realtimePushService;

    public ImCommandDispatcher(ImMessageService imMessageService,
                               FileCardMessageService fileCardMessageService,
                               ImRealtimePushService realtimePushService) {
        this.imMessageService = imMessageService;
        this.fileCardMessageService = fileCardMessageService;
        this.realtimePushService = realtimePushService;
    }

    public ImCommandResult dispatch(ImCommandRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "消息请求不能为空");
        }
        if (SEND_TEXT.equals(request.type())) {
            return handleSendText(request);
        }
        if (SEND_FILE_CARD.equals(request.type())) {
            return handleSendFileCard(request);
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的消息类型: " + request.type());
    }

    private ImCommandResult handleSendText(ImCommandRequest request) {
        Long userId = requireLogin(request.userId());
        Long conversationId = requireConversationId(request.conversationId());
        String clientMessageId = requireClientMessageId(request.clientMessageId());
        String content = request.payload() == null ? null : request.payload().path("content").asText(null);
        List<Long> mentions = readLongList(request.payload(), "mentions");

        ImMessageService.StoreMessageResult result = imMessageService.storeTextMessage(
                userId,
                conversationId,
                clientMessageId,
                content,
                mentions
        );
        realtimePushService.pushMessageReceived(result.memberUserIds(), result.message());
        return new ImCommandResult(request.requestId(), clientMessageId, conversationId, result.messageId());
    }

    private ImCommandResult handleSendFileCard(ImCommandRequest request) {
        Long userId = requireLogin(request.userId());
        Long conversationId = requireConversationId(request.conversationId());
        String clientMessageId = requireClientMessageId(request.clientMessageId());
        if (request.payload() == null || !request.payload().has("fileIds")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "fileIds 不能为空");
        }
        List<Long> fileIds = readLongList(request.payload(), "fileIds");

        ImMessageService.StoreMessageResult result = fileCardMessageService.storeFileCardMessage(
                userId,
                conversationId,
                clientMessageId,
                fileIds
        );
        realtimePushService.pushMessageReceived(result.memberUserIds(), result.message());
        return new ImCommandResult(request.requestId(), clientMessageId, conversationId, result.messageId());
    }

    private Long requireLogin(Long userId) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.NO_LOGIN, "未登录");
        }
        return userId;
    }

    private Long requireConversationId(Long conversationId) {
        if (conversationId == null || conversationId <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "conversationId 不能为空");
        }
        return conversationId;
    }

    private String requireClientMessageId(String clientMessageId) {
        if (!StringUtils.hasText(clientMessageId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "clientMessageId 不能为空");
        }
        return clientMessageId;
    }

    private List<Long> readLongList(JsonNode payload, String fieldName) {
        JsonNode node = payload == null ? null : payload.path(fieldName);
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<Long> result = new ArrayList<>();
        node.forEach(item -> result.add(item.asLong()));
        return result;
    }
}
