package uno.acloud.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import uno.acloud.user.entity.User;

import java.io.Serializable;
import java.util.List;

@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * Override selectById to delegate to getById, which excludes the password hash.
     * BaseMapper.selectById does SELECT * and would expose the password field.
     */
    @Override
    default User selectById(Serializable id) {
        return getById((Long) id);
    }

    @Select("SELECT id, username, name, email, phone, avatar, email_verified, phone_verified, default_team_id, create_time FROM `user` WHERE id=#{id}")
    User getById(Long id);

    @Select("""
            <script>
            SELECT id, username, name
            FROM `user`
            WHERE id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">
                #{id}
            </foreach>
            </script>
            """)
    List<User> listByIds(@Param("ids") List<Long> ids);

    @Select("SELECT id, username, name, email, phone, avatar, email_verified, phone_verified, default_team_id, create_time FROM `user` WHERE username=#{username}")
    User getByUsername(String username);

    @Select("""
            SELECT id, username, name, email, phone, avatar, email_verified, phone_verified, default_team_id, create_time, password
            FROM `user`
            WHERE username = #{identifier}
               OR (email_verified = 1 AND email = #{identifier})
               OR (phone_verified = 1 AND phone = #{identifier})
            ORDER BY CASE WHEN username = #{identifier} THEN 0 ELSE 1 END, id ASC
            LIMIT 1
            """)
    User getByLoginIdentifier(String identifier);

    @Select("""
            <script>
            SELECT id, username, name, avatar
            FROM `user`
            WHERE
                <choose>
                    <when test="numericKeyword != null">
                        id = #{numericKeyword}
                        OR username LIKE CONCAT(#{keyword}, '%')
                    </when>
                    <otherwise>
                        username LIKE CONCAT(#{keyword}, '%')
                    </otherwise>
                </choose>
            ORDER BY id ASC
            LIMIT #{limit}
            </script>
            """)
    List<User> searchUsers(@Param("keyword") String keyword,
                           @Param("numericKeyword") Long numericKeyword,
                           @Param("limit") int limit);

    @Insert("INSERT INTO `user`(username,password,create_time) VALUES(#{username},#{password},#{createTime})")
    @org.apache.ibatis.annotations.Options(useGeneratedKeys = true, keyProperty = "id")
    int addByUsernameAndPassword(User user);

    @Insert("""
            INSERT INTO `user`(username, password, name, email, phone, default_team_id, create_time)
            VALUES(#{username}, #{password}, #{name}, #{email}, #{phone}, #{defaultTeamId}, #{createTime})
            """)
    @org.apache.ibatis.annotations.Options(useGeneratedKeys = true, keyProperty = "id")
    int insertTeamUser(User user);

    @Update("UPDATE `user` SET name = #{name} WHERE id = #{userId}")
    int updateName(@Param("userId") Long userId, @Param("name") String name);

    @Update("UPDATE `user` SET name = #{name}, avatar = #{avatar} WHERE id = #{userId}")
    int updateProfile(@Param("userId") Long userId,
                      @Param("name") String name,
                      @Param("avatar") String avatar);

    @Update("UPDATE `user` SET password = #{password} WHERE id = #{userId}")
    int updatePassword(@Param("userId") Long userId, @Param("password") String password);

    @Update("UPDATE `user` SET email = #{email}, email_verified = 0 WHERE id = #{userId}")
    int updateEmail(@Param("userId") Long userId, @Param("email") String email);

    @Update("UPDATE `user` SET phone = #{phone}, phone_verified = 0 WHERE id = #{userId}")
    int updatePhone(@Param("userId") Long userId, @Param("phone") String phone);

    @Update("UPDATE `user` SET email_verified = 1 WHERE id = #{userId} AND email IS NOT NULL")
    int verifyEmail(@Param("userId") Long userId);

    @Update("UPDATE `user` SET phone_verified = 1 WHERE id = #{userId} AND phone IS NOT NULL")
    int verifyPhone(@Param("userId") Long userId);

    @Update("UPDATE `user` SET default_team_id = #{teamId} WHERE id = #{userId}")
    int updateDefaultTeam(@Param("userId") Long userId, @Param("teamId") Long teamId);

    @Select("SELECT COUNT(*) FROM `user`")
    int countUsers();

    @Select("SELECT id FROM `user` ORDER BY id ASC")
    List<Long> listAllUserIds();

    @Delete("DELETE FROM `user` WHERE id = #{id}")
    int deleteById(@Param("id") Long id);

    @Select("""
            SELECT DISTINCT email
            FROM `user`
            WHERE email_verified = 1
              AND email IS NOT NULL
              AND email <> ''
            ORDER BY email ASC
            """)
    List<String> listVerifiedEmails();

    @Insert("""
            INSERT INTO contact_verification_code(user_id, contact_type, code, expire_time, create_time)
            VALUES(#{userId}, #{type}, #{code}, DATE_ADD(NOW(), INTERVAL 10 MINUTE), NOW())
            ON DUPLICATE KEY UPDATE code = VALUES(code), expire_time = VALUES(expire_time), create_time = VALUES(create_time)
            """)
    int upsertContactVerificationCode(@Param("userId") Long userId,
                                      @Param("type") String type,
                                      @Param("code") String code);

    @Select("""
            SELECT COUNT(*)
            FROM contact_verification_code
            WHERE user_id = #{userId}
              AND contact_type = #{type}
              AND code = #{code}
              AND expire_time >= NOW()
            """)
    int countValidContactVerificationCode(@Param("userId") Long userId,
                                          @Param("type") String type,
                                          @Param("code") String code);

    @Select("""
            SELECT DISTINCT linked.*
            FROM `user` cu
            JOIN `user` linked ON linked.id <> cu.id
             AND (
                (cu.email_verified = 1 AND linked.email_verified = 1 AND cu.email IS NOT NULL AND cu.email = linked.email)
                OR
                (cu.phone_verified = 1 AND linked.phone_verified = 1 AND cu.phone IS NOT NULL AND cu.phone = linked.phone)
             )
            WHERE cu.id = #{userId}
            ORDER BY linked.id ASC
            """)
    List<User> listLinkedAccounts(@Param("userId") Long userId);

    @Select("""
            SELECT COUNT(*)
            FROM `user` cu
            JOIN `user` target ON target.id = #{targetUserId}
             AND (
                (cu.email_verified = 1 AND target.email_verified = 1 AND cu.email IS NOT NULL AND cu.email = target.email)
                OR
                (cu.phone_verified = 1 AND target.phone_verified = 1 AND cu.phone IS NOT NULL AND cu.phone = target.phone)
             )
            WHERE cu.id = #{userId}
            """)
    int countVerifiedLinkedAccount(@Param("userId") Long userId,
                                   @Param("targetUserId") Long targetUserId);

    @Insert("""
            INSERT INTO account_switch_trust(source_user_id, target_user_id, create_time)
            VALUES(#{sourceUserId}, #{targetUserId}, NOW())
            ON DUPLICATE KEY UPDATE create_time = VALUES(create_time)
            """)
    int upsertAccountSwitchTrust(@Param("sourceUserId") Long sourceUserId,
                                 @Param("targetUserId") Long targetUserId);

    @Select("""
            SELECT COUNT(*)
            FROM account_switch_trust
            WHERE source_user_id = #{sourceUserId}
              AND target_user_id = #{targetUserId}
            """)
    int countAccountSwitchTrust(@Param("sourceUserId") Long sourceUserId,
                                @Param("targetUserId") Long targetUserId);

    @Delete("DELETE FROM contact_verification_code WHERE expire_time < NOW()")
    int deleteExpiredContactVerificationCodes();
}
