package uno.acloud.project.service.impl;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
@AllArgsConstructor
class ProjectCreateRequestReviewResultPayload {
    private Long teamId;
    private Long applicationId;
    private Long reviewerUserId;
    private Boolean approved;
    private Long projectId;
    private String projectName;
    private String reviewReason;
}
