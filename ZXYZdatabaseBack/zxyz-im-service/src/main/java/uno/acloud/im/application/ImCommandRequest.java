package uno.acloud.im.application;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 应用层入站命令，隔离 WebSocket 协议帧与业务编排。
 */
public record ImCommandRequest(Long userId,
                               String authorization,
                               String type,
                               String requestId,
                               String clientMessageId,
                               Long conversationId,
                               JsonNode payload) {
}
