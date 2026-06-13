package uno.acloud.im.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uno.acloud.common.Result;
import uno.acloud.im.application.WsTicketService;
import uno.acloud.im.config.ImAuthContext;
import uno.acloud.satoken.AuthServicePort;

@SaCheckLogin
@RestController
@RequestMapping("/api/im")
@Tag(name = "WebSocket", description = "WebSocket 认证票据")
public class WsTicketController {

    private final WsTicketService wsTicketService;
    private final AuthServicePort authServicePort;

    public WsTicketController(WsTicketService wsTicketService, AuthServicePort authServicePort) {
        this.wsTicketService = wsTicketService;
        this.authServicePort = authServicePort;
    }

    @Operation(summary = "创建 WebSocket 认证票据")
    @PostMapping("/ws/ticket")
    public Result<String> createTicket() {
        Long userId = ImAuthContext.currentUserId();
        String saToken = authServicePort.getTokenValue();
        String ticket = wsTicketService.createTicket(userId, saToken);
        return Result.success(ticket);
    }
}
