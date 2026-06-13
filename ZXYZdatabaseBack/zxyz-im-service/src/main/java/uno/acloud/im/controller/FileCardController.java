package uno.acloud.im.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uno.acloud.common.Result;
import uno.acloud.im.application.FileCardMessageService;
import uno.acloud.im.config.ImAuthContext;
import uno.acloud.im.dto.MessageFileCardResolveRequest;
import uno.acloud.im.vo.FileCardResolveVO;

@SaCheckLogin
@RestController
@RequestMapping("/api/im")
@Tag(name = "文件卡片", description = "IM 消息中的文件卡片解析")
public class FileCardController {

    private final FileCardMessageService fileCardMessageService;

    public FileCardController(FileCardMessageService fileCardMessageService) {
        this.fileCardMessageService = fileCardMessageService;
    }

    @Operation(summary = "解析文件卡片")
    @PostMapping("/messages/{messageId}/file-card/resolve")
    public Result<FileCardResolveVO> resolveFileCard(@PathVariable Long messageId,
                                                     @Valid @RequestBody(required = false) MessageFileCardResolveRequest request) {
        Long targetMessageId = request != null && request.getMessageId() != null ? request.getMessageId() : messageId;
        return Result.of(fileCardMessageService.resolveFileCardMessage(
                ImAuthContext.currentUserId(),
                targetMessageId
        ));
    }
}
