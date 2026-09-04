package uno.acloud.team.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import uno.acloud.common.ErrorCode;
import static uno.acloud.common.TeamErrorCode.*;
import uno.acloud.common.UserErrorCode;
import uno.acloud.common.TeamPermissionCodes;
import uno.acloud.common.TeamRoleCodes;
import uno.acloud.common.util.TransactionHelper;
import uno.acloud.common.oss.AvatarUploadSignService;
import uno.acloud.dto.UserInfoDTO;
import uno.acloud.exception.BusinessException;
import uno.acloud.team.dto.team.CreateTeamMemberRequest;
import uno.acloud.team.dto.team.CreateTeamRequest;
import uno.acloud.team.dto.team.UpdateTeamMemberStatusRequest;
import uno.acloud.team.entity.Team;
import uno.acloud.team.entity.TeamMember;
import uno.acloud.team.entity.TeamQuota;
import uno.acloud.team.infrastructure.client.FileServiceClient;
import uno.acloud.team.infrastructure.client.ProjectServiceClient;
import uno.acloud.team.infrastructure.client.UserServiceClient;
import uno.acloud.team.infrastructure.mapper.TeamEntityMapper;
import uno.acloud.team.infrastructure.mq.TeamEventPublisher;
import uno.acloud.team.mapper.TeamMapper;
import uno.acloud.team.mapper.TeamQuotaMapper;
import uno.acloud.team.mapper.TeamUserDefaultSyncMapper;
import uno.acloud.team.service.TeamFileAccessPort;
import uno.acloud.team.vo.team.TeamMemberVO;
import uno.acloud.team.vo.team.TeamVO;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EnterpriseTeamServiceTest {

    @Mock
    private TeamMapper teamMapper;

    @Mock
    private TeamQuotaMapper quotaMapper;

    @Mock
    private UserServiceClient userServiceClient;

    @Mock
    private ProjectServiceClient projectServiceClient;

    @Mock
    private FileServiceClient fileServiceClient;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private TeamPermissionManager teamPermissionService;

    @Mock
    private TeamFileAccessPort teamFileAccessService;

    @Mock
    private TeamEventPublisher teamEventPublisher;

    @Mock
    private AvatarUploadSignService avatarUploadSignService;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private TeamEntityMapper teamEntityMapper;

    @Mock
    private TransactionHelper transactionHelper;

    @Mock
    private TeamUserDefaultSyncMapper teamUserDefaultSyncMapper;

    @Mock
    private RLock rLock;

    private EnterpriseTeamService enterpriseTeamService;

    @BeforeEach
    void setUp() {
        enterpriseTeamService = new EnterpriseTeamService(
                teamMapper, quotaMapper, userServiceClient, projectServiceClient,
                fileServiceClient, passwordEncoder, teamPermissionService,
                teamFileAccessService, teamEventPublisher, avatarUploadSignService,
                redissonClient, teamEntityMapper, transactionHelper, teamUserDefaultSyncMapper, 100, 107374182400L, 6);
        // Mock TransactionHelper to execute lambdas directly (simulates transactional behavior)
        lenient().when(transactionHelper.execute(any())).thenAnswer(invocation -> {
            TransactionHelper.TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        lenient().doAnswer(invocation -> {
            TransactionHelper.TransactionVoidCallback callback = invocation.getArgument(0);
            callback.doInTransaction(null);
            return null;
        }).when(transactionHelper).executeWithoutResult(any());
    }

    // ==================== createTeam — distributed lock acquired ====================

    @Test
    void createTeam_withDistributedLock_shouldAcquireLockBeforeCreating() throws Exception {
        CreateTeamRequest request = new CreateTeamRequest();
        request.setName("TestTeam");
        request.setOwnerUsername("admin");
        request.setOwnerPassword("password123");

        // Lock setup
        when(redissonClient.getLock("zxyz:team:create:TestTeam")).thenReturn(rLock);
        when(rLock.tryLock(5L, 30L, TimeUnit.SECONDS)).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        // User-level lock for upsertMember
        RLock userLock = mock(RLock.class);
        when(redissonClient.getLock("zxyz:team:member:user:1")).thenReturn(userLock);
        when(userLock.tryLock(5L, 30L, TimeUnit.SECONDS)).thenReturn(true);
        when(userLock.isHeldByCurrentThread()).thenReturn(true);

        // Password encoding
        when(passwordEncoder.encode("password123")).thenReturn("encoded_password");

        // User creation
        UserInfoDTO owner = new UserInfoDTO();
        owner.setId(1L);
        owner.setUsername("admin");
        when(userServiceClient.createTeamUser(eq("admin"), eq("encoded_password"),
                isNull(), isNull(), isNull(), isNull())).thenReturn(owner);

        // Mock DB operations inside transaction
        when(teamMapper.countCurrentMemberships(1L)).thenReturn(0);
        doAnswer(invocation -> {
            Team arg = invocation.getArgument(0);
            arg.setId(10L);
            return null;
        }).when(teamMapper).insert(any(Team.class));
        doNothing().when(teamPermissionService).initializeBuiltInRoles(eq(10L), eq(1L));
        Team team = new Team();
        team.setId(10L);
        team.setName("TestTeam");
        team.setOwnerUserId(1L);
        team.setStatus(0);
        when(teamMapper.selectById(10L)).thenReturn(team);

        // TeamVO conversion
        when(teamPermissionService.listRoleCodes(10L, 1L)).thenReturn(List.of(TeamRoleCodes.OWNER));
        when(teamPermissionService.listPermissionCodes(10L, 1L)).thenReturn(List.of());
        when(teamEntityMapper.toTeamVO(any(Team.class))).thenAnswer(invocation -> {
            Team t = invocation.getArgument(0);
            return new TeamVO(t.getId(), t.getName(), null, null, t.getOwnerUserId(), 0, "", List.of(), null, null);
        });

        TeamVO result = enterpriseTeamService.createTeam(request);

        assertNotNull(result);
        assertEquals(10L, result.getId());
        assertEquals("TestTeam", result.getName());

        // Verify lock was acquired and released
        verify(rLock).tryLock(5L, 30L, TimeUnit.SECONDS);
        verify(rLock).unlock();
        // Verify user was created
        verify(userServiceClient).createTeamUser(eq("admin"), eq("encoded_password"),
                isNull(), isNull(), isNull(), isNull());
    }

    // ============ createTeam — 补偿记录须与团队数据同事务写入（N3 回归锁） ============

    @Test
    void createTeam_shouldUpsertPendingSyncRecordInsideTransaction() throws Exception {
        CreateTeamRequest request = new CreateTeamRequest();
        request.setName("TestTeam");
        request.setOwnerUsername("admin");
        request.setOwnerPassword("password123");

        when(redissonClient.getLock("zxyz:team:create:TestTeam")).thenReturn(rLock);
        when(rLock.tryLock(5L, 30L, TimeUnit.SECONDS)).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        RLock userLock = mock(RLock.class);
        when(redissonClient.getLock("zxyz:team:member:user:1")).thenReturn(userLock);
        when(userLock.tryLock(5L, 30L, TimeUnit.SECONDS)).thenReturn(true);
        when(userLock.isHeldByCurrentThread()).thenReturn(true);

        when(passwordEncoder.encode("password123")).thenReturn("encoded_password");
        UserInfoDTO owner = new UserInfoDTO();
        owner.setId(1L);
        owner.setUsername("admin");
        when(userServiceClient.createTeamUser(eq("admin"), eq("encoded_password"),
                isNull(), isNull(), isNull(), isNull())).thenReturn(owner);

        when(teamMapper.countCurrentMemberships(1L)).thenReturn(0);
        doAnswer(invocation -> {
            Team arg = invocation.getArgument(0);
            arg.setId(10L);
            return null;
        }).when(teamMapper).insert(any(Team.class));
        doNothing().when(teamPermissionService).initializeBuiltInRoles(eq(10L), eq(1L));
        Team team = new Team();
        team.setId(10L);
        team.setName("TestTeam");
        team.setOwnerUserId(1L);
        team.setStatus(0);
        when(teamMapper.selectById(10L)).thenReturn(team);
        when(teamPermissionService.listRoleCodes(10L, 1L)).thenReturn(List.of(TeamRoleCodes.OWNER));
        when(teamPermissionService.listPermissionCodes(10L, 1L)).thenReturn(List.of());
        when(teamEntityMapper.toTeamVO(any(Team.class))).thenAnswer(invocation -> {
            Team t = invocation.getArgument(0);
            return new TeamVO(t.getId(), t.getName(), null, null, t.getOwnerUserId(), 0, "", List.of(), null, null);
        });

        // 关键断言：在事务 lambda 返回（尚未提交）时快照，确认补偿记录已经写入。
        // 旧实现把 upsertPending 放在 afterCommit：那是提交之后另开连接写库，
        // 一旦失败只留 log.error，重试任务永久丢失（N3）。
        // 注意必须用 doAnswer 风格重新 stub：when(mock.execute(any())) 会对 mock
        // 发生真实调用（参数求值为 null），命中 setUp 里的旧 stub 并在其 answer 中 NPE。
        AtomicBoolean writtenBeforeCommit = new AtomicBoolean(false);
        doAnswer(invocation -> {
            TransactionHelper.TransactionCallback<?> callback = invocation.getArgument(0);
            Object result = callback.doInTransaction(null);
            writtenBeforeCommit.set(mockingDetails(teamUserDefaultSyncMapper).getInvocations().stream()
                    .anyMatch(i -> "upsertPending".equals(i.getMethod().getName())));
            return result;
        }).when(transactionHelper).execute(any());

        enterpriseTeamService.createTeam(request);

        assertTrue(writtenBeforeCommit.get(),
                "team_user_default_sync 的 PENDING 记录必须在事务提交前随团队数据一同写入，" +
                        "否则重试任务会在写库失败时静默永久丢失");
        verify(teamUserDefaultSyncMapper).upsertPending(1L, 10L, "DEFAULT_TEAM:1");
    }

    // ==================== createTeam — duplicate name ====================

    @Test
    void createTeam_withDuplicateName_shouldHandleGracefully() throws Exception {
        CreateTeamRequest request = new CreateTeamRequest();
        request.setName("ExistingTeam");
        request.setOwnerUsername("admin");
        request.setOwnerPassword("password123");

        when(redissonClient.getLock("zxyz:team:create:ExistingTeam")).thenReturn(rLock);
        when(rLock.tryLock(5L, 30L, TimeUnit.SECONDS)).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(passwordEncoder.encode("password123")).thenReturn("encoded_password");

        // Simulate duplicate username
        when(userServiceClient.createTeamUser(eq("admin"), eq("encoded_password"),
                isNull(), isNull(), isNull(), isNull()))
                .thenThrow(new DuplicateKeyException("Duplicate entry"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> enterpriseTeamService.createTeam(request));
        assertEquals(UserErrorCode.USERNAME_EXISTS.getCode(), ex.getErrorCode());
        assertTrue(ex.getMessage().contains("已存在"));

        // Lock should still be released
        verify(rLock).unlock();
        // Transactional method should never be called
        verify(teamMapper, never()).insert(any(Team.class));
    }

    // ==================== createMember — distributed lock acquired ====================

    @Test
    void createMember_withDistributedLock_shouldAcquireLockBeforeCreating() throws Exception {
        Long teamId = 10L;
        Long operatorUserId = 1L;
        Long newUserId = 2L;

        CreateTeamMemberRequest request = new CreateTeamMemberRequest();
        request.setUsername("newuser");
        request.setPassword("password123");
        request.setRoleCode("team_member");

        // Permission check
        doNothing().when(teamFileAccessService).check(operatorUserId, teamId, TeamPermissionCodes.TEAM_MEMBER_CREATE);

        // Team exists
        Team team = new Team();
        team.setId(teamId);
        team.setName("TestTeam");
        team.setOwnerUserId(operatorUserId);
        team.setStatus(0);
        when(teamMapper.selectById(teamId)).thenReturn(team);

        // Lock setup
        when(redissonClient.getLock("zxyz:team:member:" + teamId)).thenReturn(rLock);
        when(rLock.tryLock(5L, 30L, TimeUnit.SECONDS)).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        // User-level lock for upsertMember
        RLock userLock = mock(RLock.class);
        when(redissonClient.getLock("zxyz:team:member:user:" + newUserId)).thenReturn(userLock);
        when(userLock.tryLock(5L, 30L, TimeUnit.SECONDS)).thenReturn(true);
        when(userLock.isHeldByCurrentThread()).thenReturn(true);

        // Quota check
        TeamQuota quota = new TeamQuota();
        quota.setTeamId(teamId);
        quota.setMemberLimit(100);
        when(quotaMapper.getByTeamId(teamId)).thenReturn(quota);
        when(teamMapper.countOccupiedMembers(teamId)).thenReturn(5);

        // Password encoding
        when(passwordEncoder.encode("password123")).thenReturn("encoded_password");

        // User creation
        UserInfoDTO newUser = new UserInfoDTO();
        newUser.setId(newUserId);
        newUser.setUsername("newuser");
        when(userServiceClient.createTeamUser(eq("newuser"), eq("encoded_password"),
                isNull(), isNull(), isNull(), eq(teamId))).thenReturn(newUser);

        // Mock DB operations inside transaction
        TeamMember createdMember = new TeamMember();
        createdMember.setTeamId(teamId);
        createdMember.setUserId(newUserId);
        createdMember.setRoleCode("team_member");
        createdMember.setStatus(0);
        createdMember.setJoinTime(LocalDateTime.now());
        when(teamMapper.getActiveMember(teamId, newUserId)).thenReturn(createdMember);

        TeamMemberVO memberVO = new TeamMemberVO(newUserId, "newuser", null, null, null, "team_member", 0, LocalDateTime.now());
        when(teamEntityMapper.toMemberVO(eq(createdMember), any())).thenReturn(memberVO);

        TeamMemberVO result = enterpriseTeamService.createMember(teamId, request, operatorUserId);

        assertNotNull(result);
        assertEquals(newUserId, result.getUserId());

        // Verify lock was acquired and released
        verify(rLock).tryLock(5L, 30L, TimeUnit.SECONDS);
        verify(rLock).unlock();
        // Verify user was created via HTTP call
        verify(userServiceClient).createTeamUser(eq("newuser"), eq("encoded_password"),
                isNull(), isNull(), isNull(), eq(teamId));
    }

    // ==================== createMember — lock acquisition failure ====================

    @Test
    void createMember_lockNotAcquired_shouldThrowConcurrentOperation() throws Exception {
        Long teamId = 10L;
        Long operatorUserId = 1L;

        CreateTeamMemberRequest request = new CreateTeamMemberRequest();
        request.setUsername("newuser");
        request.setPassword("password123");

        doNothing().when(teamFileAccessService).check(operatorUserId, teamId, TeamPermissionCodes.TEAM_MEMBER_CREATE);

        Team team = new Team();
        team.setId(teamId);
        team.setOwnerUserId(operatorUserId);
        team.setStatus(0);
        when(teamMapper.selectById(teamId)).thenReturn(team);

        // Lock NOT acquired
        when(redissonClient.getLock("zxyz:team:member:" + teamId)).thenReturn(rLock);
        when(rLock.tryLock(5L, 30L, TimeUnit.SECONDS)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> enterpriseTeamService.createMember(teamId, request, operatorUserId));
        assertEquals(ErrorCode.CONCURRENT_OPERATION, ex.getErrorCode());

        // User should never be created
        verify(userServiceClient, never()).createTeamUser(anyString(), anyString(), any(), any(), any(), any());
    }

    // ==================== createMember — member limit reached ====================

    @Test
    void createMember_memberLimitReached_shouldReject() throws Exception {
        Long teamId = 10L;
        Long operatorUserId = 1L;

        CreateTeamMemberRequest request = new CreateTeamMemberRequest();
        request.setUsername("newuser");
        request.setPassword("password123");

        doNothing().when(teamFileAccessService).check(operatorUserId, teamId, TeamPermissionCodes.TEAM_MEMBER_CREATE);

        Team team = new Team();
        team.setId(teamId);
        team.setOwnerUserId(operatorUserId);
        team.setStatus(0);
        when(teamMapper.selectById(teamId)).thenReturn(team);

        when(redissonClient.getLock("zxyz:team:member:" + teamId)).thenReturn(rLock);
        when(rLock.tryLock(5L, 30L, TimeUnit.SECONDS)).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        // Member limit reached
        TeamQuota quota = new TeamQuota();
        quota.setTeamId(teamId);
        quota.setMemberLimit(10);
        when(quotaMapper.getByTeamId(teamId)).thenReturn(quota);
        when(teamMapper.countOccupiedMembers(teamId)).thenReturn(10);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> enterpriseTeamService.createMember(teamId, request, operatorUserId));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("名额已满"));

        // Lock should be released
        verify(rLock).unlock();
        // User should never be created
        verify(userServiceClient, never()).createTeamUser(anyString(), anyString(), any(), any(), any(), any());
    }

    // ==================== removeMember — distributed lock acquired ====================

    @Test
    void removeMember_withDistributedLock_shouldAcquireLockBeforeRemoving() throws Exception {
        Long teamId = 10L;
        Long targetUserId = 2L;
        Long operatorUserId = 1L;

        doNothing().when(teamFileAccessService).check(operatorUserId, teamId, TeamPermissionCodes.TEAM_MEMBER_REMOVE);

        Team team = new Team();
        team.setId(teamId);
        team.setOwnerUserId(operatorUserId);
        team.setStatus(0);
        when(teamMapper.selectById(teamId)).thenReturn(team);

        // Target is not a project leader
        when(projectServiceClient.countActiveProjectsLedBy(targetUserId)).thenReturn(0);

        // Lock setup
        when(redissonClient.getLock("zxyz:team:member:" + teamId)).thenReturn(rLock);
        when(rLock.tryLock(5L, 30L, TimeUnit.SECONDS)).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        // Mock deactivateMember DB operation
        when(teamMapper.removeMember(teamId, targetUserId)).thenReturn(1);

        enterpriseTeamService.removeMember(teamId, targetUserId, operatorUserId);

        // Verify lock was acquired and released
        verify(rLock).tryLock(5L, 30L, TimeUnit.SECONDS);
        verify(rLock).unlock();
        // Verify deactivation was called (member removed from DB)
        verify(teamMapper).removeMember(teamId, targetUserId);
    }

    // ==================== removeMember — cannot remove owner ====================

    @Test
    void removeMember_targetIsOwner_shouldReject() {
        Long teamId = 10L;
        Long ownerUserId = 1L;

        doNothing().when(teamFileAccessService).check(ownerUserId, teamId, TeamPermissionCodes.TEAM_MEMBER_REMOVE);

        Team team = new Team();
        team.setId(teamId);
        team.setOwnerUserId(ownerUserId);
        team.setStatus(0);
        when(teamMapper.selectById(teamId)).thenReturn(team);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> enterpriseTeamService.removeMember(teamId, ownerUserId, ownerUserId));
        assertEquals(TEAM_PERMISSION_DENIED.getCode(), ex.getErrorCode());
        assertTrue(ex.getMessage().contains("不能移除团队大管理员"));
    }

    // ==================== removeMember — target is project leader ====================

    @Test
    void removeMember_targetIsProjectLeader_shouldReject() throws Exception {
        Long teamId = 10L;
        Long targetUserId = 2L;
        Long operatorUserId = 1L;

        doNothing().when(teamFileAccessService).check(operatorUserId, teamId, TeamPermissionCodes.TEAM_MEMBER_REMOVE);

        Team team = new Team();
        team.setId(teamId);
        team.setOwnerUserId(operatorUserId);
        team.setStatus(0);
        when(teamMapper.selectById(teamId)).thenReturn(team);

        // Target is still a project leader
        when(projectServiceClient.countActiveProjectsLedBy(targetUserId)).thenReturn(2);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> enterpriseTeamService.removeMember(teamId, targetUserId, operatorUserId));
        assertEquals(TEAM_PERMISSION_DENIED.getCode(), ex.getErrorCode());
        assertTrue(ex.getMessage().contains("项目负责人"));
    }

    // ==================== updateMemberStatus — lock acquisition failure ====================

    @Test
    void updateMemberStatus_lockNotAcquired_shouldThrowConcurrentOperation() throws Exception {
        Long teamId = 10L;
        Long targetUserId = 2L;
        Long operatorUserId = 1L;

        UpdateTeamMemberStatusRequest request = new UpdateTeamMemberStatusRequest();
        request.setStatus(1);

        doNothing().when(teamFileAccessService).check(operatorUserId, teamId, TeamPermissionCodes.TEAM_MEMBER_REMOVE);

        when(redissonClient.getLock("zxyz:team:member:" + teamId)).thenReturn(rLock);
        when(rLock.tryLock(5L, 30L, TimeUnit.SECONDS)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> enterpriseTeamService.updateMemberStatus(teamId, targetUserId, request, operatorUserId));
        assertEquals(ErrorCode.CONCURRENT_OPERATION, ex.getErrorCode());
    }

    // ==================== createTeam — lock not acquired ====================

    @Test
    void createTeam_lockNotAcquired_shouldThrowConcurrentOperation() throws Exception {
        CreateTeamRequest request = new CreateTeamRequest();
        request.setName("TestTeam");
        request.setOwnerUsername("admin");
        request.setOwnerPassword("password123");

        when(redissonClient.getLock("zxyz:team:create:TestTeam")).thenReturn(rLock);
        when(rLock.tryLock(5L, 30L, TimeUnit.SECONDS)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> enterpriseTeamService.createTeam(request));
        assertEquals(ErrorCode.CONCURRENT_OPERATION, ex.getErrorCode());

        // User should never be created
        verify(userServiceClient, never()).createTeamUser(anyString(), anyString(), any(), any(), any(), any());
    }
}
