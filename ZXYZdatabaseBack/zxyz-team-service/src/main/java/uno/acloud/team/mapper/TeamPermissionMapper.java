package uno.acloud.team.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import uno.acloud.team.entity.TeamPermissionAudit;
import uno.acloud.team.entity.TeamPermissionEntity;
import uno.acloud.team.entity.TeamRoleEntity;

import java.util.List;

@Mapper
public interface TeamPermissionMapper {

    // ==================== 已有方法 ====================

    @Insert("""
            INSERT INTO team_role(team_id, role_name, role_code, description, builtin, create_time, update_time)
            VALUES(#{teamId}, #{roleName}, #{roleCode}, #{description}, 1, NOW(), NOW())
            ON DUPLICATE KEY UPDATE role_name = VALUES(role_name), description = VALUES(description), builtin = 1
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int upsertRole(TeamRoleSeed role);

    @Select("SELECT id FROM team_role WHERE team_id = #{teamId} AND role_code = #{roleCode} LIMIT 1")
    Long getRoleId(@Param("teamId") Long teamId, @Param("roleCode") String roleCode);

    @Select("SELECT id FROM team_permission WHERE permission_code = #{permissionCode} LIMIT 1")
    Integer getPermissionId(@Param("permissionCode") String permissionCode);

    @Insert("""
            INSERT INTO team_permission(permission_name, permission_code, description)
            VALUES(#{permissionName}, #{permissionCode}, #{description})
            ON DUPLICATE KEY UPDATE
                permission_name = VALUES(permission_name),
                description = VALUES(description)
            """)
    int upsertPermission(TeamPermissionSeed permission);

    @Update("DELETE FROM team_role_permission WHERE team_id = #{teamId} AND team_role_id = #{roleId}")
    int deleteRolePermissions(@Param("teamId") Long teamId, @Param("roleId") Long roleId);

    @Insert("""
            INSERT IGNORE INTO team_role_permission(team_id, team_role_id, permission_id, create_time)
            VALUES(#{teamId}, #{roleId}, #{permissionId}, NOW())
            """)
    int insertRolePermission(@Param("teamId") Long teamId, @Param("roleId") Long roleId, @Param("permissionId") Integer permissionId);

    @Update("DELETE FROM team_member_role WHERE team_id = #{teamId} AND user_id = #{userId}")
    int deleteMemberRoles(@Param("teamId") Long teamId, @Param("userId") Long userId);

    @Insert("""
            INSERT INTO team_member_role(team_id, user_id, team_role_id, create_time)
            VALUES(#{teamId}, #{userId}, #{roleId}, NOW())
            """)
    int insertMemberRole(@Param("teamId") Long teamId, @Param("userId") Long userId, @Param("roleId") Long roleId);

    @Select("""
            SELECT DISTINCT tr.role_code
            FROM team_member_role tmr
            JOIN team_role tr ON tr.id = tmr.team_role_id
            JOIN team_member tm ON tm.team_id = tmr.team_id AND tm.user_id = tmr.user_id AND tm.status = 0
            WHERE tmr.user_id = #{userId}
              AND tmr.team_id = #{teamId}
            """)
    List<String> getTeamRoleCodes(@Param("userId") long userId, @Param("teamId") long teamId);

    @Select("""
            SELECT DISTINCT tp.permission_code
            FROM team_member_role tmr
            JOIN team_role tr ON tr.id = tmr.team_role_id
            JOIN team_role_permission trp ON trp.team_id = tmr.team_id AND trp.team_role_id = tr.id
            JOIN team_permission tp ON tp.id = trp.permission_id
            JOIN team_member tm ON tm.team_id = tmr.team_id AND tm.user_id = tmr.user_id AND tm.status = 0
            WHERE tmr.user_id = #{userId}
              AND tmr.team_id = #{teamId}
            """)
    List<String> getTeamPermissionCodes(@Param("userId") long userId, @Param("teamId") long teamId);

    // ==================== 新增方法：权限定义查询 ====================

    /** 列出所有权限定义 */
    @Select("""
            SELECT id, permission_name, permission_code, description, create_time, update_time
            FROM team_permission
            ORDER BY permission_code ASC
            """)
    List<TeamPermissionEntity> listPermissions();

    /** 按 code 查询权限定义 */
    @Select("""
            SELECT id, permission_name, permission_code, description, create_time, update_time
            FROM team_permission
            WHERE permission_code = #{permissionCode}
            LIMIT 1
            """)
    TeamPermissionEntity getPermissionByCode(@Param("permissionCode") String permissionCode);

    // ==================== 新增方法：角色 CRUD ====================

    /** 列出团队所有角色 */
    @Select("""
            SELECT id, team_id, role_name, role_code, description, builtin, create_time, update_time
            FROM team_role
            WHERE team_id = #{teamId}
            ORDER BY builtin DESC, role_code ASC
            """)
    List<TeamRoleEntity> listRoles(@Param("teamId") Long teamId);

    /** 按 code 查询角色 */
    @Select("""
            SELECT id, team_id, role_name, role_code, description, builtin, create_time, update_time
            FROM team_role
            WHERE team_id = #{teamId} AND role_code = #{roleCode}
            LIMIT 1
            """)
    TeamRoleEntity getRoleByCode(@Param("teamId") Long teamId, @Param("roleCode") String roleCode);

    /** 按 ID 查询角色 */
    @Select("""
            SELECT id, team_id, role_name, role_code, description, builtin, create_time, update_time
            FROM team_role
            WHERE id = #{roleId} AND team_id = #{teamId}
            LIMIT 1
            """)
    TeamRoleEntity getRoleById(@Param("teamId") Long teamId, @Param("roleId") Long roleId);

    /** 新增角色（支持非内置角色） */
    @Insert("""
            INSERT INTO team_role(team_id, role_name, role_code, description, builtin, create_time, update_time)
            VALUES(#{teamId}, #{roleName}, #{roleCode}, #{description}, #{builtin}, NOW(), NOW())
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertRole(TeamRoleEntity role);

    /** 更新角色 */
    @Update("""
            UPDATE team_role
            SET role_name = #{roleName},
                description = #{description},
                update_time = NOW()
            WHERE id = #{id} AND team_id = #{teamId}
            """)
    int updateRole(TeamRoleEntity role);

    /** 删除非内置角色 */
    @Update("DELETE FROM team_role WHERE id = #{roleId} AND team_id = #{teamId} AND builtin = 0")
    int deleteRole(@Param("teamId") Long teamId, @Param("roleId") Long roleId);

    /** 列出角色的权限 code 列表 */
    @Select("""
            SELECT tp.permission_code
            FROM team_role_permission trp
            JOIN team_permission tp ON tp.id = trp.permission_id
            WHERE trp.team_id = #{teamId} AND trp.team_role_id = #{roleId}
            ORDER BY tp.permission_code ASC
            """)
    List<String> listRolePermissionCodes(@Param("teamId") Long teamId, @Param("roleId") Long roleId);

    // ==================== 新增方法：成员权限查询 ====================

    /** 检查成员是否有某权限 */
    @Select("""
            SELECT COUNT(*)
            FROM team_member_role tmr
            JOIN team_role_permission trp ON trp.team_id = tmr.team_id AND trp.team_role_id = tmr.team_role_id
            JOIN team_permission tp ON tp.id = trp.permission_id
            WHERE tmr.team_id = #{teamId}
              AND tmr.user_id = #{userId}
              AND tp.permission_code = #{permissionCode}
            """)
    int countMemberPermission(@Param("teamId") Long teamId,
                              @Param("userId") Long userId,
                              @Param("permissionCode") String permissionCode);

    /** 列出成员所有权限 code */
    @Select("""
            SELECT tp.permission_code
            FROM team_member_role tmr
            JOIN team_role_permission trp ON trp.team_id = tmr.team_id AND trp.team_role_id = tmr.team_role_id
            JOIN team_permission tp ON tp.id = trp.permission_id
            WHERE tmr.team_id = #{teamId} AND tmr.user_id = #{userId}
            ORDER BY tp.permission_code ASC
            """)
    List<String> listMemberPermissionCodes(@Param("teamId") Long teamId, @Param("userId") Long userId);

    /** 获取成员角色 code */
    @Select("""
            SELECT tr.role_code
            FROM team_member_role tmr
            JOIN team_role tr ON tr.id = tmr.team_role_id
            WHERE tmr.team_id = #{teamId} AND tmr.user_id = #{userId}
            LIMIT 1
            """)
    String getMemberRoleCode(@Param("teamId") Long teamId, @Param("userId") Long userId);

    // ==================== 新增方法：审计 ====================

    /** 写入审计记录 */
    @Insert("""
            INSERT INTO team_permission_audit(team_id, operator_id, operation_type, target_type, target_id, before_value, after_value, operation_time)
            VALUES(#{teamId}, #{operatorId}, #{operationType}, #{targetType}, #{targetId}, #{beforeValue}, #{afterValue}, #{operationTime})
            """)
    int insertAudit(TeamPermissionAudit audit);

    /** 查询审计记录 */
    @Select("""
            SELECT id, team_id, operator_id, operation_type, target_type, target_id, before_value, after_value, operation_time
            FROM team_permission_audit
            WHERE team_id = #{teamId}
            ORDER BY operation_time DESC, id DESC
            LIMIT #{limit}
            """)
    List<TeamPermissionAudit> listAudit(@Param("teamId") Long teamId, @Param("limit") int limit);

    // ==================== 批量操作 ====================

    @Select("<script>" +
            "SELECT id, permission_code FROM team_permission WHERE permission_code IN " +
            "<foreach collection='codes' item='code' open='(' separator=',' close=')'>" +
            "#{code}" +
            "</foreach>" +
            "</script>")
    List<TeamPermissionEntity> getPermissionIdsByCodes(@Param("codes") List<String> codes);

    @Insert("<script>" +
            "INSERT IGNORE INTO team_role_permission (team_id, team_role_id, permission_id, create_time) VALUES " +
            "<foreach collection='permissionIds' item='pid' separator=','>" +
            "(#{teamId}, #{roleId}, #{pid}, NOW())" +
            "</foreach>" +
            "</script>")
    int batchInsertRolePermissions(@Param("teamId") Long teamId, @Param("roleId") Long roleId, @Param("permissionIds") List<Integer> permissionIds);

    @Insert("<script>" +
            "INSERT INTO team_permission(permission_name, permission_code, description) VALUES " +
            "<foreach collection='seeds' item='s' separator=','>" +
            "(#{s.permissionName}, #{s.permissionCode}, #{s.description})" +
            "</foreach>" +
            "ON DUPLICATE KEY UPDATE permission_name=VALUES(permission_name), description=VALUES(description)" +
            "</script>")
    int batchUpsertPermissions(@Param("seeds") List<TeamPermissionSeed> seeds);

    // ==================== 内部类 ====================

    class TeamRoleSeed {
        public Long id;
        public Long teamId;
        public String roleName;
        public String roleCode;
        public String description;
    }

    class TeamPermissionSeed {
        public String permissionName;
        public String permissionCode;
        public String description;
    }
}
