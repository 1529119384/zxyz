package uno.acloud.im.infrastructure.netty.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import uno.acloud.im.vo.ConversationReadVO;
import uno.acloud.im.vo.FileCardEntryVO;
import uno.acloud.im.vo.ImMessageVO;
import uno.acloud.im.vo.MessageRecallVO;

@Component
public class ImEnvelopeFactory {

    private final ObjectMapper objectMapper;

    public ImEnvelopeFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ObjectNode authOk(Long userId, int connectionCount) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("userId", userId);
        payload.put("connectionCount", connectionCount);
        return envelope("AUTH_OK", null, null, null, payload);
    }

    public ObjectNode pong(ImEnvelope request) {
        JsonNode payload = request.getPayload() == null ? objectMapper.createObjectNode() : request.getPayload();
        return envelope("PONG", request.getRequestId(), null, request.getConversationId(), payload);
    }

    public ObjectNode error(String requestId, String message) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("message", message);
        return envelope("ERROR", requestId, null, null, payload);
    }

    public ObjectNode messageAck(String requestId,
                                 String clientMessageId,
                                 Long conversationId,
                                 Long messageId) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("messageId", messageId);
        payload.put("status", "STORED");
        return envelope("MESSAGE_ACK", requestId, clientMessageId, conversationId, payload);
    }

    public ObjectNode messageReceived(ImMessageVO message) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("messageId", message.getMessageId());
        payload.put("senderUserId", message.getSenderUserId());
        putNullable(payload, "senderUsername", message.getSenderUsername());
        putNullable(payload, "senderName", message.getSenderName());
        putNullable(payload, "senderAvatar", message.getSenderAvatar());
        payload.put("messageType", message.getMessageType());
        payload.putPOJO("mentions", message.getMentions() == null ? java.util.List.of() : message.getMentions());
        if (message.getContent() == null) {
            payload.putNull("content");
        } else {
            payload.put("content", message.getContent());
        }
        if (message.getFileCard() == null) {
            payload.putNull("fileCard");
        } else {
            ObjectNode fileCard = objectMapper.createObjectNode();
            fileCard.put("shareType", message.getFileCard().getShareType());
            if (message.getFileCard().getOwnerUserId() == null) {
                fileCard.putNull("ownerUserId");
            } else {
                fileCard.put("ownerUserId", message.getFileCard().getOwnerUserId());
            }
            if (message.getFileCard().getParentId() == null) {
                fileCard.putNull("parentId");
            } else {
                fileCard.put("parentId", message.getFileCard().getParentId());
            }
            fileCard.put("entryCount", message.getFileCard().getEntryCount() == null ? 0 : message.getFileCard().getEntryCount());
            fileCard.putPOJO("entries", message.getFileCard().getEntries().stream().map(this::toEntryMap).toList());
            payload.set("fileCard", fileCard);
        }
        payload.put("status", message.getStatus() == null ? "STORED" : message.getStatus());
        if (message.getRecallByUserId() == null) {
            payload.putNull("recallByUserId");
        } else {
            payload.put("recallByUserId", message.getRecallByUserId());
        }
        if (message.getRecallTime() == null) {
            payload.putNull("recallTime");
        } else {
            payload.put("recallTime", message.getRecallTime().toString());
        }
        putNullable(payload, "recallReason", message.getRecallReason());
        payload.put("readByPeer", Boolean.TRUE.equals(message.getReadByPeer()));
        payload.put("readCount", message.getReadCount() == null ? 0 : message.getReadCount());
        if (message.getCreateTime() == null) {
            payload.putNull("createTime");
        } else {
            payload.put("createTime", message.getCreateTime().toString());
        }
        return envelope("MESSAGE_RECEIVED", null, message.getClientMessageId(), message.getConversationId(), payload);
    }

    public ObjectNode readUpdated(ConversationReadVO readState) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("conversationId", readState.getConversationId());
        payload.put("readerUserId", readState.getReaderUserId());
        payload.put("lastReadMessageId", readState.getLastReadMessageId());
        return envelope("READ_UPDATED", null, null, readState.getConversationId(), payload);
    }

    public ObjectNode messageRecalled(MessageRecallVO recall) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("messageId", recall.getMessageId());
        payload.put("recallByUserId", recall.getRecallByUserId());
        if (recall.getRecallTime() == null) {
            payload.putNull("recallTime");
        } else {
            payload.put("recallTime", recall.getRecallTime().toString());
        }
        putNullable(payload, "recallReason", recall.getRecallReason());
        return envelope("MESSAGE_RECALLED", null, null, recall.getConversationId(), payload);
    }

    private java.util.Map<String, Object> toEntryMap(FileCardEntryVO entry) {
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("fileId", entry.getFileId());
        result.put("fileType", entry.getFileType());
        result.put("originalName", entry.getOriginalName());
        result.put("category", entry.getCategory());
        result.put("fileSize", entry.getFileSize());
        result.put("parentId", entry.getParentId());
        result.put("storePath", entry.getStorePath());
        result.put("modifyTime", entry.getModifyTime() == null ? null : entry.getModifyTime().toString());
        return result;
    }

    private void putNullable(ObjectNode payload, String fieldName, String value) {
        if (value == null) {
            payload.putNull(fieldName);
        } else {
            payload.put(fieldName, value);
        }
    }

    private ObjectNode envelope(String type,
                                String requestId,
                                String clientMessageId,
                                Long conversationId,
                                JsonNode payload) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", type);
        if (requestId == null) {
            root.putNull("requestId");
        } else {
            root.put("requestId", requestId);
        }
        if (clientMessageId == null) {
            root.putNull("clientMessageId");
        } else {
            root.put("clientMessageId", clientMessageId);
        }
        if (conversationId == null) {
            root.putNull("conversationId");
        } else {
            root.put("conversationId", conversationId);
        }
        root.set("payload", payload == null ? objectMapper.createObjectNode() : payload);
        root.put("timestamp", System.currentTimeMillis());
        return root;
    }
}
