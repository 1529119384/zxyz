package uno.acloud.team.aop;

import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import uno.acloud.common.permission.TeamPermissionAspect;
import uno.acloud.common.permission.TeamPermissionPort;
import uno.acloud.satoken.AuthServicePort;

@Aspect
@Component
public class RequiresTeamPermissionAspect extends TeamPermissionAspect {

    public RequiresTeamPermissionAspect(TeamPermissionPort teamPermissionPort, AuthServicePort authServicePort) {
        super(teamPermissionPort, authServicePort);
    }
}
