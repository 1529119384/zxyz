package uno.acloud.im.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.acloud.common.TeamErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.im.infrastructure.persistence.entity.TeamInviteLink;
import uno.acloud.im.infrastructure.mapper.ConversationMapper;
import uno.acloud.im.infrastructure.mapper.TeamManagementMapper;
import uno.acloud.im.infrastructure.mapper.TeamMapper;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 60 号 🟡-6 防回归：默认邀请链接在 usedCount 达到 maxUses 后，加入申请被拒
 * 且复用既有「邀请链接无效」语义（TEAM_INVITATION_INVALID / 4403）。
 */
@ExtendWith(MockitoExtension.class)
class JoinRequestServiceInviteLinkMaxUsesTest {

    private static final Long USER_ID = 7L;

    @Mock
    private TeamMapper teamMapper;
    @Mock
    private TeamManagementMapper managementMapper;
    @Mock
    private ConversationMapper conversationMapper;
    @Mock
    private TeamPermissionService teamPermissionService;
    @Mock
    private SystemNotificationService notificationService;
    @Mock
    private ImDomainEventPublisher domainEventPublisher;

    private JoinRequestService service;

    @BeforeEach
    void setUp() {
        // 60 号 🟠-1 整改后构造器新增 @Lazy 自代理参数；单测传 null（无事务环境下 DB 段走 self 代理的
        // 事务注解由 Spring 容器承担，纯 mock 测试不触发）
        service = new JoinRequestService(teamMapper, managementMapper, conversationMapper,
                teamPermissionService, notificationService, domainEventPublisher, null);
    }

    @Test
    void submitJoinRequestShouldRejectWhenDefaultMaxUsesReached() {
        when(managementMapper.getInviteLinkByToken("token-a"))
                .thenReturn(activeLink(50, 50));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.submitJoinRequest(USER_ID, "token-a"));

        assertEquals(TeamErrorCode.TEAM_INVITATION_INVALID.getCode(), exception.getErrorCode());
        assertEquals("邀请链接使用次数已达上限", exception.getMessage());
        verifyNoInteractions(teamMapper, notificationService, domainEventPublisher);
    }

    @Test
    void submitJoinRequestShouldAllowWhenUnderMaxUses() {
        when(managementMapper.getInviteLinkByToken("token-a"))
                .thenReturn(activeLink(50, 49));
        when(teamMapper.getActiveMember(6L, USER_ID)).thenReturn(null);
        when(managementMapper.getPendingJoinRequest(6L, USER_ID)).thenReturn(null);
        when(teamMapper.listActiveMemberUserIds(6L)).thenReturn(java.util.List.of());

        // 测试环境无活动事务，TransactionUtils.runAfterCommit 静态退化为同步执行（失败仅记日志）。

        service.submitJoinRequest(USER_ID, "token-a");

        org.mockito.Mockito.verify(managementMapper).insertJoinRequest(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void submitJoinRequestShouldStillAllowUnlimitedLegacyLinks() {
        // 历史 maxUses=0 的存量链接语义不变：0 = 不限次。
        when(managementMapper.getInviteLinkByToken("legacy"))
                .thenReturn(activeLink(0, 999999));
        when(teamMapper.getActiveMember(6L, USER_ID)).thenReturn(null);
        when(managementMapper.getPendingJoinRequest(6L, USER_ID)).thenReturn(null);
        when(teamMapper.listActiveMemberUserIds(6L)).thenReturn(java.util.List.of());

        service.submitJoinRequest(USER_ID, "legacy");

        org.mockito.Mockito.verify(managementMapper).insertJoinRequest(org.mockito.ArgumentMatchers.any());
    }

    private TeamInviteLink activeLink(int maxUses, int usedCount) {
        TeamInviteLink link = new TeamInviteLink();
        link.setId(11L);
        link.setTeamId(6L);
        link.setToken("token-a");
        link.setStatus(0);
        link.setExpireTime(LocalDateTime.now().plusHours(1));
        link.setMaxUses(maxUses);
        link.setUsedCount(usedCount);
        return link;
    }
}
