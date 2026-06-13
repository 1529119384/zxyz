package uno.acloud.project.service.impl;

import org.springframework.stereotype.Component;

@Component
public class ProjectCollaborationCoordinator {

    private final ImCollaborationClient imCollaborationClient;

    public ProjectCollaborationCoordinator(ImCollaborationClient imCollaborationClient) {
        this.imCollaborationClient = imCollaborationClient;
    }

    public Long createProjectConversation(Long projectId, Long teamId, String projectName, Long leaderUserId) {
        return imCollaborationClient.createProjectConversation(projectId, teamId, projectName, leaderUserId);
    }

    public void archiveProjectConversation(Long projectId) {
        imCollaborationClient.archiveProjectConversation(projectId);
    }

    public void appendProjectCreateRequestMessage(ProjectCreateRequestMessagePayload payload) {
        imCollaborationClient.appendProjectCreateRequestMessage(payload);
    }

    public void appendProjectCreateRequestReviewResultMessage(ProjectCreateRequestReviewResultPayload payload) {
        imCollaborationClient.appendProjectCreateRequestReviewResultMessage(payload);
    }
}
