package uno.acloud.team.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import uno.acloud.team.entity.PermissionAuditEntity;
import uno.acloud.team.entity.PermissionEntity;
import uno.acloud.team.entity.RoleEntity;

import java.util.List;

@Mapper
public interface PermissionRoleMapper {

    @Select("""
            SELECT r.role_code
            FROM user_role AS ur
            JOIN role AS r ON r.id = ur.role_id
            WHERE ur.user_id = #{userId};""")
    List<String> getRoleByUserID(long userId);

    @Select("""
            SELECT DISTINCT p.permission_code
            FROM permission AS p
            JOIN role_permission AS rp ON rp.permission_id = p.id
            JOIN user_role AS ur ON ur.role_id = rp.role_id
            WHERE ur.user_id = #{userId};""")
    List<String> getPermissionByUserID(long userId);

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

    @Select("""
            SELECT id, permission_name, permission_code, description, create_time, update_time
            FROM permission
            ORDER BY permission_code ASC
            """)
    List<PermissionEntity> listPermissions();

    @Select("""
            SELECT id, role_name, role_code, description, create_time, update_time
            FROM role
            ORDER BY role_code ASC
            """)
    List<RoleEntity> listRoles();

    @Select("""
            SELECT p.permission_code
            FROM role_permission rp
            JOIN permission p ON p.id = rp.permission_id
            WHERE rp.role_id = #{roleId}
            ORDER BY p.permission_code ASC
            """)
    List<String> listPermissionCodesByRoleId(@Param("roleId") Integer roleId);

    @Select("""
            SELECT id, role_name, role_code, description, create_time, update_time
            FROM role
            WHERE role_code = #{roleCode}
            LIMIT 1
            """)
    RoleEntity getRoleByCode(@Param("roleCode") String roleCode);

    @Select("""
            SELECT id, role_name, role_code, description, create_time, update_time
            FROM role
            WHERE id = #{roleId}
            LIMIT 1
            """)
    RoleEntity getRoleById(@Param("roleId") Integer roleId);

    @Select("""
            SELECT id, permission_name, permission_code, description, create_time, update_time
            FROM permission
            WHERE permission_code = #{permissionCode}
            LIMIT 1
            """)
    PermissionEntity getPermissionByCode(@Param("permissionCode") String permissionCode);

    @Insert("""
            INSERT INTO role(role_name, role_code, description, create_time, update_time)
            VALUES(#{roleName}, #{roleCode}, #{description}, NOW(), NOW())
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertRole(RoleEntity role);

    @Update("""
            UPDATE role
            SET role_name = #{roleName},
                description = #{description},
                update_time = NOW()
            WHERE id = #{id}
            """)
    int updateRole(RoleEntity role);

    @Update("DELETE FROM role_permission WHERE role_id = #{roleId}")
    int deleteRolePermissions(@Param("roleId") Integer roleId);

    @Update("DELETE FROM role WHERE id = #{roleId}")
    int deleteRole(@Param("roleId") Integer roleId);

    @Insert("""
            INSERT INTO role_permission(role_id, permission_id, create_time)
            VALUES(#{roleId}, #{permissionId}, NOW())
            """)
    int insertRolePermission(@Param("roleId") Integer roleId, @Param("permissionId") Integer permissionId);

    @Update("DELETE FROM user_role WHERE user_id = #{userId}")
    int deleteUserRoles(@Param("userId") Long userId);

    @Insert("""
            INSERT INTO user_role(user_id, role_id, create_time)
            VALUES(#{userId}, #{roleId}, NOW())
            """)
    int insertUserRole(@Param("userId") Long userId, @Param("roleId") Integer roleId);

    @Select("SELECT COUNT(*) FROM user_role WHERE user_id = #{userId}")
    int countUserRoles(@Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM user_role WHERE role_id = #{roleId}")
    int countUsersByRoleId(@Param("roleId") Integer roleId);

    @Select("""
            SELECT ur.user_id
            FROM user_role ur
            JOIN role r ON r.id = ur.role_id
            WHERE r.role_code = #{roleCode}
            """)
    List<Long> listUserIdsByRoleCode(@Param("roleCode") String roleCode);

    @Select("""
            SELECT COUNT(*) FROM user_role ur
            JOIN role r ON r.id = ur.role_id
            WHERE r.role_code = #{roleCode}
            """)
    int countUsersByRoleCode(@Param("roleCode") String roleCode);

    @Insert("""
            INSERT INTO permission_audit(operator_id, scope_type, operation_type, target_type, target_id, before_value, after_value, operation_time, ip_address)
            VALUES(#{operatorId}, #{scopeType}, #{operationType}, #{targetType}, #{targetId}, #{beforeValue}, #{afterValue}, #{operationTime}, #{ipAddress})
            """)
    int insertAudit(PermissionAuditEntity auditEntity);

    @Select("""
            SELECT id, operator_id, scope_type, operation_type, target_type, target_id, before_value, after_value, operation_time, ip_address
            FROM permission_audit
            ORDER BY operation_time DESC, id DESC
            LIMIT #{limit}
            """)
    List<PermissionAuditEntity> listAudit(@Param("limit") int limit);

    @Insert("""
            INSERT IGNORE INTO permission(permission_name, permission_code, description, create_time, update_time)
            VALUES(#{permissionName}, #{permissionCode}, #{description}, NOW(), NOW())
            """)
    int insertPermissionIgnore(@Param("permissionName") String permissionName,
                               @Param("permissionCode") String permissionCode,
                               @Param("description") String description);

    @Select("SELECT id FROM permission WHERE permission_code = #{permissionCode} LIMIT 1")
    Integer getPermissionIdByCode(@Param("permissionCode") String permissionCode);

    @Insert("""
            INSERT IGNORE INTO role_permission(role_id, permission_id, create_time)
            VALUES(#{roleId}, #{permissionId}, NOW())
            """)
    int insertRolePermissionIgnore(@Param("roleId") int roleId, @Param("permissionId") int permissionId);

    @Select("<script>" +
            "SELECT id, permission_code FROM permission WHERE permission_code IN " +
            "<foreach collection='codes' item='code' open='(' separator=',' close=')'>" +
            "#{code}" +
            "</foreach>" +
            "</script>")
    List<PermissionEntity> getPermissionIdsByCodes(@Param("codes") List<String> codes);

    @Insert("<script>" +
            "INSERT IGNORE INTO role_permission (role_id, permission_id, create_time) VALUES " +
            "<foreach collection='permissionIds' item='pid' separator=','>" +
            "(#{roleId}, #{pid}, NOW())" +
            "</foreach>" +
            "</script>")
    int batchInsertRolePermissionsIgnore(@Param("roleId") int roleId, @Param("permissionIds") List<Integer> permissionIds);

    @Insert("<script>" +
            "INSERT INTO role_permission (role_id, permission_id, create_time) VALUES " +
            "<foreach collection='permissionIds' item='pid' separator=','>" +
            "(#{roleId}, #{pid}, NOW())" +
            "</foreach>" +
            "</script>")
    int batchInsertRolePermissions(@Param("roleId") Integer roleId, @Param("permissionIds") List<Integer> permissionIds);
}
