package uno.acloud.file.service.impl;

import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.FileSpaceType;
import uno.acloud.common.TeamPermissionCodes;
import uno.acloud.exception.BusinessException;
import uno.acloud.file.infrastructure.entity.FileNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
public class FileAccessGuard {

    private final TeamServicePermissionClient teamServicePermissionClient;
    private final ProjectAccessCacheService projectAccessCacheService;

    public FileAccessGuard(TeamServicePermissionClient teamServicePermissionClient,
                           ProjectAccessCacheService projectAccessCacheService) {
        this.teamServicePermissionClient = teamServicePermissionClient;
        this.projectAccessCacheService = projectAccessCacheService;
    }

    public void requireReadAccess(FileNode fileNode, Long userId) {
        requireNodeAccess(fileNode, userId, AccessMode.READ);
    }

    public void requireWriteAccess(FileNode fileNode, Long userId) {
        requireNodeAccess(fileNode, userId, AccessMode.WRITE);
    }

    public void requireDeleteAccess(FileNode fileNode, Long userId) {
        requireNodeAccess(fileNode, userId, AccessMode.DELETE);
    }

    public void requireReadAccess(List<FileNode> fileNodes, Long userId) {
        if (fileNodes == null) {
            return;
        }
        requireBatchAccess(fileNodes, userId, AccessMode.READ);
    }

    public void requireWriteAccess(List<FileNode> fileNodes, Long userId) {
        if (fileNodes == null) {
            return;
        }
        requireBatchAccess(fileNodes, userId, AccessMode.WRITE);
    }

    public void requireDeleteAccess(List<FileNode> fileNodes, Long userId) {
        if (fileNodes == null) {
            return;
        }
        requireBatchAccess(fileNodes, userId, AccessMode.DELETE);
    }

    public void requireTeamViewPermission(Long teamId, Long userId) {
        if (teamId == null) return;
        AccessMode.READ.checkTeamPermission(teamServicePermissionClient, teamId, userId);
    }

    public void requireTeamWritePermission(Long teamId, Long userId) {
        if (teamId == null) return;
        AccessMode.WRITE.checkTeamPermission(teamServicePermissionClient, teamId, userId);
    }

    public void requireTeamDeletePermission(Long teamId, Long userId) {
        if (teamId == null) return;
        AccessMode.DELETE.checkTeamPermission(teamServicePermissionClient, teamId, userId);
    }

    public void requireProjectFileAccess(Long projectId, Long userId) {
        projectAccessCacheService.checkAccess(projectId, userId);
    }

    public void requireSameSpace(List<FileNode> nodes, Long targetTeamId) {
        for (FileNode node : nodes) {
            if (!java.util.Objects.equals(node.getTeamId(), targetTeamId)) {
                throw new BusinessException(ErrorCode.FILE_STATE_INVALID, "不能在个人空间和团队空间之间移动或复制文件");
            }
        }
    }

    private void requireBatchAccess(List<FileNode> fileNodes, Long userId, AccessMode accessMode) {
        Set<Long> projectIds = new LinkedHashSet<>();
        Set<Long> teamIds = new LinkedHashSet<>();
        List<FileNode> personalNodes = new ArrayList<>();

        for (FileNode node : fileNodes) {
            if (node == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "文件不存在");
            }
            if (FileSpaceType.isProject(node.getSpaceType())) {
                if (node.getProjectId() == null) {
                    throw new BusinessException(ErrorCode.FILE_STATE_INVALID, "项目空间文件缺少项目归属");
                }
                projectIds.add(node.getProjectId());
            } else if (node.getTeamId() != null) {
                teamIds.add(node.getTeamId());
            } else {
                personalNodes.add(node);
            }
        }

        for (Long projectId : projectIds) {
            projectAccessCacheService.checkAccess(projectId, userId);
        }

        for (Long teamId : teamIds) {
            accessMode.checkTeamPermission(teamServicePermissionClient, teamId, userId);
        }

        for (FileNode node : personalNodes) {
            requirePersonalOwner(node, userId);
        }
    }

    private void requireNodeAccess(FileNode fileNode, Long userId, AccessMode accessMode) {
        if (fileNode == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文件不存在");
        }
        if (FileSpaceType.isProject(fileNode.getSpaceType())) {
            if (fileNode.getProjectId() == null) {
                throw new BusinessException(ErrorCode.FILE_STATE_INVALID, "项目空间文件缺少项目归属");
            }
            projectAccessCacheService.checkAccess(fileNode.getProjectId(), userId);
            return;
        }
        if (fileNode.getTeamId() != null) {
            accessMode.checkTeamPermission(teamServicePermissionClient, fileNode.getTeamId(), userId);
            return;
        }
        requirePersonalOwner(fileNode, userId);
    }

    private void requirePersonalOwner(FileNode fileNode, Long userId) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.NO_LOGIN, "用户未登录");
        }
        if (!userId.equals(fileNode.getUploadUserId())) {
            throw new BusinessException(ErrorCode.NO_PERMISSION, "无权访问该个人空间文件");
        }
    }

    private enum AccessMode {
        READ {
            @Override
            void checkTeamPermission(TeamServicePermissionClient client, Long teamId, Long userId) {
                checkWithLogging(client, teamId, userId, TeamPermissionCodes.TEAM_FILE_READ);
            }
        },
        WRITE {
            @Override
            void checkTeamPermission(TeamServicePermissionClient client, Long teamId, Long userId) {
                checkWithLogging(client, teamId, userId, TeamPermissionCodes.TEAM_FILE_WRITE);
            }
        },
        DELETE {
            @Override
            void checkTeamPermission(TeamServicePermissionClient client, Long teamId, Long userId) {
                checkWithLogging(client, teamId, userId, TeamPermissionCodes.TEAM_FILE_DELETE);
            }
        };

        abstract void checkTeamPermission(TeamServicePermissionClient client, Long teamId, Long userId);

        private static void checkWithLogging(TeamServicePermissionClient client, Long teamId, Long userId, String permissionCode) {
            try {
                client.check(userId, teamId, permissionCode);
            } catch (Exception e) {
                log.warn("团队权限校验 HTTP 调用失败: teamId={}, userId={}, permission={}", teamId, userId, permissionCode, e);
                throw e;
            }
        }
    }
}
