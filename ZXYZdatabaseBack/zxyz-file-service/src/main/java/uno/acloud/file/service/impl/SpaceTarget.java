package uno.acloud.file.service.impl;

import uno.acloud.common.FileSpaceType;
import uno.acloud.file.infrastructure.entity.FileNode;

record SpaceTarget(Long teamId, Integer spaceType, Long projectId) {

    static SpaceTarget fromRequest(Long teamId, Integer spaceType, Long projectId) {
        return new SpaceTarget(teamId, FileSpaceType.normalize(spaceType, teamId, projectId), projectId);
    }

    static SpaceTarget fromNode(FileNode fileNode) {
        return new SpaceTarget(
                fileNode.getTeamId(),
                FileSpaceType.normalize(fileNode.getSpaceType(), fileNode.getTeamId(), fileNode.getProjectId()),
                fileNode.getProjectId()
        );
    }

    Long ownerUserId(Long userId) {
        return FileSpaceType.isPersonal(spaceType) ? userId : null;
    }
}
