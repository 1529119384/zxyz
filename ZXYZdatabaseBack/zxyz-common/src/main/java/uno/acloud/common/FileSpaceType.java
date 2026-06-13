package uno.acloud.common;

public final class FileSpaceType {

    public static final int PERSONAL = 1;
    public static final int TEAM = 2;
    public static final int PROJECT = 3;

    private FileSpaceType() {
    }

    public static Integer normalize(Integer spaceType, Long teamId, Long projectId) {
        if (spaceType != null) {
            return spaceType;
        }
        if (projectId != null) {
            return PROJECT;
        }
        return teamId == null ? PERSONAL : TEAM;
    }

    public static boolean isPersonal(Integer spaceType) {
        return Integer.valueOf(PERSONAL).equals(spaceType);
    }

    public static boolean isTeam(Integer spaceType) {
        return Integer.valueOf(TEAM).equals(spaceType);
    }

    public static boolean isProject(Integer spaceType) {
        return Integer.valueOf(PROJECT).equals(spaceType);
    }
}
