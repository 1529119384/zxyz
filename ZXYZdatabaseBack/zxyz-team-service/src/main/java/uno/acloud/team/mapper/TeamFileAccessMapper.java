package uno.acloud.team.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TeamFileAccessMapper {

    @Select("""
            SELECT COUNT(*)
            FROM team_member
            WHERE team_id = #{teamId}
              AND user_id = #{userId}
              AND status = 0
            """)
    int countActiveMember(@Param("teamId") Long teamId, @Param("userId") Long userId);

    @Select("""
            SELECT COUNT(*)
            FROM team_member tm
            JOIN team_member_role tmr ON tmr.team_id = tm.team_id AND tmr.user_id = tm.user_id
            JOIN team_role_permission trp ON trp.team_id = tmr.team_id AND trp.team_role_id = tmr.team_role_id
            JOIN team_permission tp ON tp.id = trp.permission_id
            WHERE tm.team_id = #{teamId}
              AND tm.user_id = #{userId}
              AND tm.status = 0
              AND tp.permission_code = #{permissionCode}
            """)
    int countPermission(@Param("teamId") Long teamId,
                        @Param("userId") Long userId,
                        @Param("permissionCode") String permissionCode);
}
