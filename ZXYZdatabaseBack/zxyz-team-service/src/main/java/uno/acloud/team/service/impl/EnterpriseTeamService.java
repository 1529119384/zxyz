package uno.acloud.team.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import uno.acloud.common.util.TransactionHelper;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.TeamPermissionCodes;
import uno.acloud.common.TeamRoleCodes;
import uno.acloud.exception.BusinessException;
import uno.acloud.exception.ForbiddenException;
import uno.acloud.exception.NotFoundException;
import uno.acloud.exception.ValidationException;
import uno.acloud.common.oss.AvatarUploadSignRequest;
import uno.acloud.common.oss.AvatarUploadSignService;
import uno.acloud.team.dto.team.CreateTeamMemberRequest;
import uno.acloud.team.dto.team.CreateTeamRequest;
import uno.acloud.team.dto.team.UpdateTeamMemberStatusRequest;
import uno.acloud.team.dto.team.UpdateTeamRequest;
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
import uno.acloud.team.service.EnterpriseTeamPort;
import uno.acloud.team.service.TeamFileAccessPort;
import uno.acloud.common.oss.OssSignInfo;
import uno.acloud.team.vo.team.TeamMemberStorageVO;
import uno.acloud.team.vo.team.TeamMemberVO;
import uno.acloud.team.vo.team.TeamVO;

import uno.acloud.dto.UserInfoDTO;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import static uno.acloud.common.InputNormalizer.optionalText;
import static uno.acloud.common.InputNormalizer.requireText;

@Service
@Slf4j
public class EnterpriseTeamService implements EnterpriseTeamPort {

    private static final int DEFAULT_MEMBER_LIMIT = 100;
    private static final long DEFAULT_STORAGE_LIMIT = 1024L * 1024L * 1024L * 100L;
    private static final int MIN_PASSWORD_LENGTH = 6;
    private static final long LOCK_WAIT_SECONDS = 5L;
    private static final long LOCK_LEASE_SECONDS = 30L;

    private final TeamMapper teamMapper;
    private final TeamQuotaMapper quotaMapper;
    private final UserServiceClient userServiceClient;
    private final ProjectServiceClient projectServiceClient;
    private final FileServiceClient fileServiceClient;
    private final PasswordEncoder passwordEncoder;
    private final TeamPermissionManager teamPermissionService;
    private final TeamFileAccessPort teamFileAccessService;
    private final TeamEventPublisher teamEventPublisher;
    private final AvatarUploadSignService avatarUploadSignService;
    private final RedissonClient redissonClient;
    private final TeamEntityMapper teamEntityMapper;
    private final TransactionHelper transactionHelper;

    public EnterpriseTeamService(TeamMapper teamMapper,
                                 TeamQuotaMapper quotaMapper,
                                 UserServiceClient userServiceClient,
                                 ProjectServiceClient projectServiceClient,
                                 FileServiceClient fileServiceClient,
                                 PasswordEncoder passwordEncoder,
                                 TeamPermissionManager teamPermissionService,
                                 TeamFileAccessPort teamFileAccessService,
                                 TeamEventPublisher teamEventPublisher,
                                 AvatarUploadSignService avatarUploadSignService,
                                 RedissonClient redissonClient,
                                 TeamEntityMapper teamEntityMapper,
                                 TransactionHelper transactionHelper) {
        this.teamMapper = teamMapper;
        this.quotaMapper = quotaMapper;
        this.userServiceClient = userServiceClient;
        this.projectServiceClient = projectServiceClient;
        this.fileServiceClient = fileServiceClient;
        this.passwordEncoder = passwordEncoder;
        this.teamPermissionService = teamPermissionService;
        this.teamFileAccessService = teamFileAccessService;
        this.teamEventPublisher = teamEventPublisher;
        this.avatarUploadSignService = avatarUploadSignService;
        this.redissonClient = redissonClient;
        this.teamEntityMapper = teamEntityMapper;
        this.transactionHelper = transactionHelper;
    }

    @Override
    public TeamVO createTeam(CreateTeamRequest request) {
        String teamName = requireText(request == null ? null : request.getName(), "团队名称不能为空");
        String ownerUsername = requireText(request == null ? null : request.getOwnerUsername(), "大管理员用户名不能为空");
        String ownerPassword = normalizePassword(request == null ? null : request.getOwnerPassword());

        // Distributed lock on team name to prevent TOCTOU race on duplicate team creation
        RLock lock = redissonClient.getLock("zxyz:team:create:" + teamName);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
            if (!acquired) {
                throw new BusinessException(ErrorCode.CONCURRENT_OPERATION, "操作过于频繁，请稍后重试");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "操作被中断");
        }
        try {
            return doCreateTeam(request, teamName, ownerUsername, ownerPassword);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private TeamVO doCreateTeam(CreateTeamRequest request, String teamName, String ownerUsername, String ownerPassword) {
        LocalDateTime now = LocalDateTime.now();

        // HTTP call — before transaction
        UserInfoDTO owner;
        try {
            owner = userServiceClient.createTeamUser(
                    ownerUsername,
                    passwordEncoder.encode(ownerPassword),
                    optionalText(request == null ? null : request.getOwnerName()),
                    optionalText(request == null ? null : request.getOwnerEmail()),
                    optionalText(request == null ? null : request.getOwnerPhone()),
                    null
            );
        } catch (DuplicateKeyException | BusinessException e) {
            if (e instanceof DuplicateKeyException
                    || (e instanceof BusinessException be && be.getErrorCode() == ErrorCode.USERNAME_EXISTS)) {
                throw new BusinessException(ErrorCode.USERNAME_EXISTS, "大管理员用户名已存在");
            }
            throw e;
        }
        Long ownerId = owner.getId();

        // DB operations — inside transaction via TransactionHelper
        Long teamId = transactionHelper.execute(status -> {
            Team team = new Team();
            team.setName(teamName);
            team.setAvatar(avatarUploadSignService.normalizeManagedAvatarUrl(
                    request == null ? null : request.getAvatar(),
                    "团队头像地址长度不能超过 512"
            ));
            team.setDescription(optionalText(request == null ? null : request.getDescription()));
            team.setOwnerUserId(ownerId);
            team.setStatus(0);
            team.setCreateTime(now);
            team.setUpdateTime(now);
            teamMapper.insert(team);

            upsertMember(team.getId(), ownerId, TeamRoleCodes.OWNER, 0, now);
            teamPermissionService.initializeBuiltInRoles(team.getId(), ownerId);

            TeamQuota quota = new TeamQuota();
            quota.setTeamId(team.getId());
            quota.setMemberLimit(normalizePositive(request == null ? null : request.getMemberLimit(), DEFAULT_MEMBER_LIMIT));
            quota.setStorageLimit(normalizePositiveLong(request == null ? null : request.getStorageLimit(), DEFAULT_STORAGE_LIMIT));
            quota.setCreateTime(now);
            quota.setUpdateTime(now);
            quotaMapper.upsertQuota(quota);

            return team.getId();
        });

        // HTTP call — after transaction, fire-and-forget with silent degradation
        try {
            userServiceClient.updateDefaultTeam(ownerId, teamId);
        } catch (Exception e) {
            log.warn("更新用户默认团队失败，userId={}, teamId={}", ownerId, teamId, e);
        }

        // Event
        Team team = teamMapper.selectById(teamId);
        teamEventPublisher.publishTeamCreated(team, owner, request);

        return toTeamVO(team, ownerId);
    }

    @Override
    public List<TeamVO> listMyTeams(Long userId) {
        return teamMapper.listMyTeams(userId).stream()
                .map(team -> toTeamVO(team, userId))
                .toList();
    }

    @Override
    public List<TeamMemberVO> listMembers(Long teamId, Long operatorUserId) {
        teamFileAccessService.check(operatorUserId, teamId, TeamPermissionCodes.TEAM_MEMBER_VIEW);
        List<TeamMember> members = teamMapper.listMembers(teamId);
        Map<Long, UserInfoDTO> userMap = buildMemberUserMap(members);
        return members.stream().map(m -> toMemberVO(m, userMap)).toList();
    }

    @Override
    public TeamVO updateTeam(Long teamId, UpdateTeamRequest request, Long operatorUserId) {
        teamFileAccessService.check(operatorUserId, teamId, TeamPermissionCodes.TEAM_UPDATE);
        Team team = requireTeam(teamId);
        team.setName(requireText(request == null ? null : request.getName(), "团队名称不能为空"));
        String avatar = request == null || request.getAvatar() == null
                ? team.getAvatar()
                : avatarUploadSignService.normalizeManagedAvatarUrl(request.getAvatar(), "团队头像地址长度不能超过 512");
        team.setAvatar(avatar);
        team.setDescription(optionalText(request == null ? null : request.getDescription(), 500, "团队描述长度不能超过 500"));
        team.setUpdateTime(LocalDateTime.now());

        // HTTP call — before DB write (read-only, only needed for event payload)
        UserInfoDTO owner = userServiceClient.getUserById(team.getOwnerUserId());

        if (teamMapper.updateTeamProfile(team) != 1) {
            throw new NotFoundException(ErrorCode.TEAM_NOT_FOUND, "团队不存在");
        }
        teamEventPublisher.publishTeamUpdated(team, owner);
        return toTeamVO(team, operatorUserId);
    }

    @Override
    public OssSignInfo getAvatarUploadSign(Long teamId, AvatarUploadSignRequest request, Long operatorUserId) {
        teamFileAccessService.check(operatorUserId, teamId, TeamPermissionCodes.TEAM_UPDATE);
        requireTeam(teamId);
        return avatarUploadSignService.generateAvatarUploadSign(request);
    }

    @Override
    public TeamMemberVO createMember(Long teamId, CreateTeamMemberRequest request, Long operatorUserId) {
        teamFileAccessService.check(operatorUserId, teamId, TeamPermissionCodes.TEAM_MEMBER_CREATE);
        Team team = requireTeam(teamId);

        RLock lock = redissonClient.getLock("zxyz:team:member:" + teamId);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
            if (!acquired) {
                throw new BusinessException(ErrorCode.CONCURRENT_OPERATION, "操作过于频繁，请稍后重试");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "操作被中断");
        }
        try {
            TeamQuota quota = quotaMapper.getByTeamId(teamId);
            int memberLimit = quota == null ? DEFAULT_MEMBER_LIMIT : quota.getMemberLimit();
            if (teamMapper.countOccupiedMembers(teamId) >= memberLimit) {
                throw new ValidationException("团队成员名额已满");
            }

            // HTTP call — before transaction
            UserInfoDTO user;
            try {
                user = userServiceClient.createTeamUser(
                        requireText(request == null ? null : request.getUsername(), "用户名不能为空"),
                        passwordEncoder.encode(normalizePassword(request == null ? null : request.getPassword())),
                        optionalText(request == null ? null : request.getName()),
                        null, null, teamId
                );
            } catch (DuplicateKeyException | BusinessException e) {
                if (e instanceof DuplicateKeyException
                        || (e instanceof BusinessException be && be.getErrorCode() == ErrorCode.USERNAME_EXISTS)) {
                    throw new BusinessException(ErrorCode.USERNAME_EXISTS, "用户名已存在");
                }
                throw e;
            }
            Long userId = user.getId();

            // DB operations — inside transaction via TransactionHelper
            TeamMemberVO result = transactionHelper.execute(status ->
                    createMemberInTransaction(teamId, userId, user, request));
            // MQ publish after transaction commit
            teamEventPublisher.publishMemberCreated(teamId, user, request);
            return result;
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private TeamMemberVO createMemberInTransaction(Long teamId, Long userId, UserInfoDTO user, CreateTeamMemberRequest request) {
        String roleCode = normalizeRoleCode(request == null ? null : request.getRoleCode());
        upsertMember(teamId, userId, roleCode, 0, LocalDateTime.now());
        teamPermissionService.assignMemberRole(teamId, userId, roleCode);

        TeamMember member = teamMapper.getActiveMember(teamId, userId);
        if (member == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "成员创建后读取失败");
        }
        return toMemberVO(member, Map.of(userId, user));
    }

    @Override
    public TeamMemberVO updateMemberStatus(Long teamId, Long targetUserId, UpdateTeamMemberStatusRequest request, Long operatorUserId) {
        teamFileAccessService.check(operatorUserId, teamId, TeamPermissionCodes.TEAM_MEMBER_REMOVE);

        RLock lock = redissonClient.getLock("zxyz:team:member:" + teamId);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
            if (!acquired) {
                throw new BusinessException(ErrorCode.CONCURRENT_OPERATION, "操作过于频繁，请稍后重试");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "操作被中断");
        }
        try {
            int status = request == null || request.getStatus() == null ? 0 : request.getStatus();
            if (status != 0 && status != 1) {
                throw new ValidationException("成员状态只能为 0 或 1");
            }
            if (teamMapper.updateMemberStatus(teamId, targetUserId, status) != 1) {
                throw new NotFoundException(ErrorCode.TEAM_NOT_FOUND, "成员不存在");
            }
            List<TeamMember> members = teamMapper.listMembers(teamId);
            Map<Long, UserInfoDTO> userMap = buildMemberUserMap(members);
            return members.stream()
                    .filter(member -> targetUserId.equals(member.getUserId()))
                    .findFirst()
                    .map(m -> toMemberVO(m, userMap))
                    .orElseThrow(() -> new NotFoundException(ErrorCode.TEAM_NOT_FOUND, "成员不存在"));
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public void removeMember(Long teamId, Long targetUserId, Long operatorUserId) {
        teamFileAccessService.check(operatorUserId, teamId, TeamPermissionCodes.TEAM_MEMBER_REMOVE);
        Team team = requireTeam(teamId);
        if (targetUserId.equals(team.getOwnerUserId())) {
            throw new ForbiddenException(ErrorCode.TEAM_PERMISSION_DENIED, "不能移除团队大管理员");
        }

        // HTTP call — guard check before transaction
        if (projectServiceClient.countActiveProjectsLedBy(targetUserId) > 0) {
            throw new ForbiddenException(ErrorCode.TEAM_PERMISSION_DENIED, "成员仍是项目负责人，请先移交负责人");
        }

        RLock lock = redissonClient.getLock("zxyz:team:member:" + teamId);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
            if (!acquired) {
                throw new BusinessException(ErrorCode.CONCURRENT_OPERATION, "操作过于频繁，请稍后重试");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "操作被中断");
        }
        try {
            // DB operations — inside transaction via TransactionHelper
            transactionHelper.executeWithoutResult(status -> {
                if (teamMapper.removeMember(teamId, targetUserId) != 1) {
                    throw new NotFoundException(ErrorCode.TEAM_NOT_FOUND, "成员不存在");
                }
                teamPermissionService.clearMemberRole(teamId, targetUserId);
            });
            // MQ publish after transaction commit
            teamEventPublisher.publishMemberRemoved(teamId, targetUserId);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public void leaveTeam(Long teamId, Long userId) {
        RLock lock = redissonClient.getLock("zxyz:team:member:" + teamId);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
            if (!acquired) {
                throw new BusinessException(ErrorCode.CONCURRENT_OPERATION, "操作过于频繁，请稍后重试");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "操作被中断");
        }
        try {
            Team team = requireTeam(teamId);
            TeamMember member = teamMapper.getActiveMember(teamId, userId);
            if (member == null) {
                throw new NotFoundException(ErrorCode.TEAM_NOT_FOUND, "团队不存在或你不在该团队中");
            }
            if (TeamRoleCodes.OWNER.equals(member.getRoleCode()) && teamMapper.countActiveOwners(teamId) <= 1) {
                throw new ForbiddenException(ErrorCode.TEAM_PERMISSION_DENIED, "最后一个大管理员不能直接退出团队");
            }
            if (projectServiceClient.countActiveProjectsLedBy(userId) > 0) {
                throw new ForbiddenException(ErrorCode.TEAM_PERMISSION_DENIED, "你仍是项目负责人，请先移交负责人");
            }
            transactionHelper.executeWithoutResult(status -> {
                if (teamMapper.removeMember(team.getId(), userId) != 1) {
                    throw new NotFoundException(ErrorCode.TEAM_NOT_FOUND, "成员不存在");
                }
                teamPermissionService.clearMemberRole(team.getId(), userId);
            });
            // MQ publish after transaction commit
            teamEventPublisher.publishMemberRemoved(teamId, userId);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public List<TeamMemberStorageVO> listMembersStorageUsage(Long teamId, Long operatorUserId) {
        teamFileAccessService.check(operatorUserId, teamId, TeamPermissionCodes.TEAM_MEMBER_VIEW);
        List<TeamMember> members = teamMapper.listMembers(teamId);
        if (members.isEmpty()) {
            return List.of();
        }

        List<Long> userIds = members.stream().map(TeamMember::getUserId).distinct().collect(Collectors.toList());
        Map<Long, Long> storageUsageMap = fileServiceClient.listPersonalStorageUsageAsMap(userIds);
        Map<Long, UserInfoDTO> userMap = buildMemberUserMap(members);

        return members.stream()
                .map(m -> {
                    UserInfoDTO user = userMap.get(m.getUserId());
                    return new TeamMemberStorageVO(
                            m.getUserId(),
                            user != null ? user.getUsername() : null,
                            user != null ? user.getName() : null,
                            user != null ? user.getAvatar() : null,
                            m.getRoleCode(),
                            storageUsageMap.getOrDefault(m.getUserId(), 0L),
                            m.getPersonalStorageLimit()
                    );
                })
                .collect(Collectors.toList());
    }

    @Override
    public void updateMemberPersonalStorageLimit(Long teamId, Long targetUserId, Long personalStorageLimit, Long operatorUserId) {
        teamFileAccessService.check(operatorUserId, teamId, TeamPermissionCodes.TEAM_STORAGE_ALLOCATE);
        TeamMember existing = teamMapper.getActiveMember(teamId, targetUserId);
        if (existing == null) {
            throw new NotFoundException(ErrorCode.TEAM_NOT_FOUND, "成员不存在");
        }
        teamMapper.updateMemberStorageLimit(teamId, targetUserId, personalStorageLimit);
    }

    public Team requireTeam(Long teamId) {
        Team team = teamMapper.selectById(teamId);
        if (team == null || !Integer.valueOf(0).equals(team.getStatus())) {
            throw new NotFoundException(ErrorCode.TEAM_NOT_FOUND, "团队不存在");
        }
        return team;
    }

    private void upsertMember(Long teamId, Long userId, String roleCode, Integer status, LocalDateTime now) {
        RLock userLock = redissonClient.getLock("zxyz:team:member:user:" + userId);
        boolean userLockAcquired = false;
        try {
            userLockAcquired = userLock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
            if (!userLockAcquired) {
                throw new BusinessException(ErrorCode.CONCURRENT_OPERATION, "操作过于频繁，请稍后重试");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "操作被中断");
        }
        try {
            if (teamMapper.countCurrentMemberships(userId) > 0 && teamMapper.getActiveMember(teamId, userId) == null) {
                throw new BusinessException(ErrorCode.TEAM_MEMBER_EXISTS, "一个账号只能属于一个团队");
            }
            TeamMember member = new TeamMember();
            member.setTeamId(teamId);
            member.setUserId(userId);
            member.setRoleCode(roleCode);
            member.setStatus(status);
            member.setJoinTime(now);
            member.setUpdateTime(now);
            try {
                teamMapper.upsertMember(member);
            } catch (DuplicateKeyException e) {
                throw new BusinessException(ErrorCode.TEAM_MEMBER_EXISTS, "一个账号只能属于一个团队");
            }
        } finally {
            if (userLockAcquired && userLock.isHeldByCurrentThread()) {
                userLock.unlock();
            }
        }
    }

    private TeamVO toTeamVO(Team team, Long userId) {
        List<String> roles = teamPermissionService.listRoleCodes(team.getId(), userId);
        String roleCode = roles.isEmpty() ? "" : roles.get(0);
        TeamVO vo = teamEntityMapper.toTeamVO(team);
        vo.setMyRoleCode(roleCode);
        vo.setMyPermissions(teamPermissionService.listPermissionCodes(team.getId(), userId));
        return vo;
    }

    /**
     * m54: 使用 MapStruct 自动生成的映射器替代手动字段拷贝。
     * TeamEntityMapper 在编译期由 MapStruct 注解处理器生成实现。
     */
    private TeamMemberVO toMemberVO(TeamMember member, Map<Long, UserInfoDTO> userMap) {
        return teamEntityMapper.toMemberVO(member, userMap);
    }

    private Map<Long, UserInfoDTO> buildMemberUserMap(List<TeamMember> members) {
        List<Long> userIds = members.stream().map(TeamMember::getUserId).distinct().toList();
        if (userIds.isEmpty()) {
            return Map.of();
        }
        List<UserInfoDTO> users = userServiceClient.listByIds(userIds);
        return users.stream()
                .collect(Collectors.toMap(UserInfoDTO::getId, Function.identity()));
    }

    private String normalizeRoleCode(String value) {
        String roleCode = optionalText(value);
        if (!StringUtils.hasText(roleCode)) {
            return TeamRoleCodes.MEMBER;
        }
        if (!TeamRoleCodes.OWNER.equals(roleCode) && !TeamRoleCodes.ADMIN.equals(roleCode) && !TeamRoleCodes.MEMBER.equals(roleCode)) {
            throw new ValidationException("团队角色不合法");
        }
        return roleCode;
    }

    private String normalizePassword(String value) {
        String password = requireText(value, "密码不能为空");
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw new ValidationException("密码不能少于 6 位");
        }
        return password;
    }

    private int normalizePositive(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private long normalizePositiveLong(Long value, long fallback) {
        return value == null || value <= 0 ? fallback : value;
    }
}
