package uno.acloud.project.service.impl;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
@AllArgsConstructor
class ProjectCreateRequestMessagePayload {
    private Long teamId;
    private Long applicationId;
    private Long requesterUserId;
    private String requesterName;
    private String projectName;
    private String description;
    private Long leaderUserId;
    private String leaderName;
    private Long storageLimit;
}
