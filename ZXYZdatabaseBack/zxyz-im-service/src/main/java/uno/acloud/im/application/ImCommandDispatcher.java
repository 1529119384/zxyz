package uno.acloud.im.application;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.im.domain.enums.ImCommandType;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class ImCommandDispatcher {

    /**
     * payload 内数组字段（mentions/fileIds）的元素上限。
     * <p>WS 帧总长只受 {@code app.im.ws.max-content-length}（默认 65536）约束，
     * 单个数组可塞约 7k 个元素，随后每个元素都要走一次 HashSet.contains / 逐条远程调用。
     * 显式封顶，避免客户端用「合法大小的帧」放大服务端工作量。</p>
     */
    private static final int MAX_PAYLOAD_LIST_SIZE = 100;

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
        ImCommandType commandType = ImCommandType.fromWireName(request.type()).orElse(null);
        if (commandType == null) {
            // 落日志保留排查所需的 type 现场；回客户端的文案不含任何外部输入（防探测/防注入）。
            log.warn("IM command 不支持的消息类型: type={}, requestId={}", request.type(), request.requestId());
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的消息类型");
        }
        return switch (commandType) {
            case SEND_TEXT -> handleSendText(request);
            case SEND_FILE_CARD -> handleSendFileCard(request);
        };
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
        if (node.size() > MAX_PAYLOAD_LIST_SIZE) {
            log.warn("IM command payload 数组超限: field={}, size={}, limit={}",
                    fieldName, node.size(), MAX_PAYLOAD_LIST_SIZE);
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    fieldName + " 数量不能超过 " + MAX_PAYLOAD_LIST_SIZE);
        }
        List<Long> result = new ArrayList<>(node.size());
        node.forEach(item -> result.add(item.asLong()));
        return result;
    }
}
