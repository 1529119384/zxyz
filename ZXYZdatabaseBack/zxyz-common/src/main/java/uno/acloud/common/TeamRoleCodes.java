package uno.acloud.common;

import java.util.Set;

public final class TeamRoleCodes {

    public static final String OWNER = "team_owner";
    public static final String ADMIN = "team_admin";
    public static final String MEMBER = "team_member";

    private static final Set<String> MANAGER_ROLES = Set.of(OWNER, ADMIN);

    private TeamRoleCodes() {
    }

    public static boolean isManager(String roleCode) {
        return MANAGER_ROLES.contains(roleCode);
    }
}
