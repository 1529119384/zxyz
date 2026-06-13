package uno.acloud.im.application;

public record ImCommandResult(String requestId,
                              String clientMessageId,
                              Long conversationId,
                              Long messageId) {
}
