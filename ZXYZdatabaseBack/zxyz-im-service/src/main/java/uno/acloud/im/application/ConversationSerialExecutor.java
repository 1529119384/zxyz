package uno.acloud.im.application;

import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 序列化必须在一个会话中保持顺序的操作
 * 当前的实现是JVM本地的
 * 以后可以在不更改应用程序服务的情况下被分片队列替换
 */
@Component
public class ConversationSerialExecutor {

    private final ConversationLockManager lockManager;

    public ConversationSerialExecutor(ConversationLockManager lockManager) {
        this.lockManager = lockManager;
    }

    public <T> T executeMessageWrite(Long conversationId, Supplier<T> supplier) {
        return lockManager.withLock("conversation-message:" + conversationId, supplier);
    }

    public <T> T executeReadUpdate(Long conversationId, Supplier<T> supplier) {
        return lockManager.withLock("conversation-read:" + conversationId, supplier);
    }
}
