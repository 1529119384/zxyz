package uno.acloud.im.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import uno.acloud.common.util.TransactionHelper;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.im.domain.enums.MessageType;
import uno.acloud.im.infrastructure.persistence.entity.FileCardArchiveEntry;
import uno.acloud.im.infrastructure.persistence.entity.FileCardContent;
import uno.acloud.im.infrastructure.persistence.entity.FileCardEntrySnapshot;
import uno.acloud.im.infrastructure.persistence.entity.FileCardResolveResult;
import uno.acloud.im.infrastructure.persistence.entity.ImMessage;
import uno.acloud.im.infrastructure.client.FileCardClient;
import uno.acloud.im.infrastructure.mapper.ConversationMapper;
import uno.acloud.im.infrastructure.mapper.ImMessageMapper;
import uno.acloud.im.vo.FileCardArchiveEntryVO;
import uno.acloud.im.vo.FileCardEntryVO;
import uno.acloud.im.vo.FileCardResolveVO;
import uno.acloud.im.vo.FileCardVO;
import uno.acloud.im.vo.ImMessageVO;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class FileCardMessageService {

    private final ConversationService conversationService;
    private final ConversationMapper conversationMapper;
    private final ImMessageMapper imMessageMapper;
    private final ConversationSerialExecutor serialExecutor;
    private final FileCardClient fileCardClient;
    private final ObjectMapper objectMapper;
    private final ImMessageService imMessageService;
    private final FileCardResolveCache fileCardResolveCache;
    private final TeamMutePolicyService mutePolicyService;
    private final TransactionHelper transactionHelper;

    public FileCardMessageService(ConversationService conversationService,
                                  ConversationMapper conversationMapper,
                                  ImMessageMapper imMessageMapper,
                                  ConversationSerialExecutor serialExecutor,
                                  FileCardClient fileCardClient,
                                  ObjectMapper objectMapper,
                                  ImMessageService imMessageService,
                                  FileCardResolveCache fileCardResolveCache,
                                  TeamMutePolicyService mutePolicyService,
                                  TransactionHelper transactionHelper) {
        this.conversationService = conversationService;
        this.conversationMapper = conversationMapper;
        this.imMessageMapper = imMessageMapper;
        this.serialExecutor = serialExecutor;
        this.fileCardClient = fileCardClient;
        this.objectMapper = objectMapper;
        this.imMessageService = imMessageService;
        this.fileCardResolveCache = fileCardResolveCache;
        this.mutePolicyService = mutePolicyService;
        this.transactionHelper = transactionHelper;
    }

    public ImMessageService.StoreMessageResult storeFileCardMessage(Long senderUserId,
                                                                    Long conversationId,
                                                                    String clientMessageId,
                                                                    List<Long> fileIds) {
        String normalizedClientMessageId = imMessageService.normalizeClientMessageId(clientMessageId);
        if (fileIds == null || fileIds.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "fileIds 不能为空");
        }
        conversationService.requireConversationMember(conversationId, senderUserId);
        conversationService.requireWritableConversation(conversationId);
        mutePolicyService.requireCanSend(senderUserId, conversationId);

        // HTTP call outside transaction to avoid holding DB connection during remote I/O
        FileCardContent fileCardContent = fileCardClient.snapshot(fileIds);

        // DB operations via transaction helper to ensure proper transaction boundary
        return transactionHelper.execute(status ->
                serialExecutor.executeMessageWrite(conversationId, () -> {
                    ImMessage existing = imMessageMapper.getByClientMessageId(conversationId, senderUserId, normalizedClientMessageId);
                    if (existing != null) {
                        ImMessageVO existingMessage = imMessageService.getMessageVOById(existing.getId());
                        return new ImMessageService.StoreMessageResult(existing.getId(), existingMessage, conversationMapper.listActiveMemberUserIds(conversationId));
                    }
                    return doStoreFileCardMessage(senderUserId, conversationId, normalizedClientMessageId, fileCardContent);
                }));
    }

    public FileCardResolveVO resolveFileCardMessage(Long userId, Long messageId) {
        if (messageId == null || messageId <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "messageId 不能为空");
        }
        ImMessage message = imMessageMapper.getById(messageId);
        if (message == null || !MessageType.FILE_CARD.equals(message.getMessageType())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文件卡片消息不存在");
        }
        conversationService.requireConversationMember(message.getConversationId(), userId);

        java.util.Optional<FileCardResolveResult> cached = fileCardResolveCache.get(messageId);
        if (cached.isPresent()) {
            return toResolveVO(cached.get());
        }

        FileCardContent content = readFileCardContent(message.getContent());
        FileCardResolveResult result = fileCardClient.resolve(content);
        Set<Long> fileIds = content.getEntries().stream().map(FileCardEntrySnapshot::getFileId).collect(Collectors.toSet());
        fileCardResolveCache.put(messageId, fileIds, result);
        return toResolveVO(result);
    }

    protected ImMessageService.StoreMessageResult doStoreFileCardMessage(Long senderUserId,
                                                                         Long conversationId,
                                                                         String clientMessageId,
                                                                         FileCardContent fileCardContent) {
        LocalDateTime now = LocalDateTime.now();
        ImMessage message = new ImMessage();
        message.setConversationId(conversationId);
        message.setSenderUserId(senderUserId);
        message.setMessageType(MessageType.FILE_CARD);
        message.setContent(writeValue(fileCardContent));
        message.setStatus(0);
        message.setClientMessageId(clientMessageId);
        message.setCreateTime(now);
        imMessageMapper.insert(message);

        conversationMapper.incrementUnreadForOthers(conversationId, senderUserId);
        conversationMapper.touchConversation(conversationId);

        ImMessageVO messageVO = imMessageService.getMessageVOById(message.getId());
        List<Long> memberUserIds = conversationMapper.listActiveMemberUserIds(conversationId);
        return new ImMessageService.StoreMessageResult(message.getId(), messageVO, memberUserIds);
    }

    private FileCardContent readFileCardContent(String rawContent) {
        return FileCardContentUtils.parse(objectMapper, rawContent);
    }

    private String writeValue(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "文件卡片消息序列化失败");
        }
    }

    private FileCardResolveVO toResolveVO(FileCardResolveResult result) {
        List<FileCardEntryVO> entries = result.getEntries() == null
                ? List.of()
                : result.getEntries().stream().map(FileCardEntryVO::fromSnapshot).toList();
        List<FileCardArchiveEntryVO> archiveEntries = result.getArchiveEntries() == null
                ? List.of()
                : result.getArchiveEntries().stream()
                .map(item -> new FileCardArchiveEntryVO(item.getFileName(), item.getArchivePath(), item.getDownloadUrl()))
                .toList();
        return new FileCardResolveVO(
                result.getStatus(),
                result.getShareType(),
                result.getTitle(),
                result.getFolderParentId(),
                result.getFolderPath(),
                result.getDownloadUrl(),
                entries,
                archiveEntries
        );
    }

}
