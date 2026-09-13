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

    /**
     * 写入/覆盖验证码<b>摘要</b>，并把「尝试次数」与「使用状态」一并复位。
     *
     * <p>复位是必须的：同一个 {@code (user_id, contact_type)} 只有一行，重发走 ON DUPLICATE KEY，
     * 若不复位 {@code attempt_count}/{@code used}，上一次把次数用光后，新发的码会一出生就是作废状态。</p>
     *
     * <p>入参是 {@code VerifyCodeHasher} 产出的 64 位十六进制摘要，明文绝不入参、绝不落库。</p>
     */
    @Insert("""
            INSERT INTO contact_verification_code(user_id, contact_type, code, attempt_count, used, used_time, expire_time, create_time)
            VALUES(#{userId}, #{type}, #{codeHash}, 0, 0, NULL, DATE_ADD(NOW(), INTERVAL 10 MINUTE), NOW())
            ON DUPLICATE KEY UPDATE code = VALUES(code),
                                    attempt_count = 0,
                                    used = 0,
                                    used_time = NULL,
                                    expire_time = VALUES(expire_time),
                                    create_time = VALUES(create_time)
            """)
    int upsertContactVerificationCode(@Param("userId") Long userId,
                                      @Param("type") String type,
                                      @Param("codeHash") String codeHash);

    /**
     * 校验第 1 步：先计一次尝试（成功与否都计，避免"猜错不计数"）。
     *
     * <p>命中存活行返回 1；无存活行（不存在 / 已使用 / 已过期）返回 0。
     * 自增后若已超过上限，顺带把该码置为已使用（作废），使爆破无法继续累积。</p>
     *
     * <p><b>IF 里用的是自增后的 {@code attempt_count}，而不是 {@code attempt_count + 1}：</b>
     * MySQL 单表 UPDATE 的 SET 子句<b>从左到右</b>求值，后一项读到的已经是自增后的值。
     * 所以判据只能是"自增后是否已超过上限" —— 这样 {@code maxAttempts} 次尝试全部可用，
     * 第 {@code maxAttempts + 1} 次才作废。</p>
     *
     * <p>{@code zxyz-email-service} 的 {@code VerifyCodeMapper.bumpAttemptCount} 原先写的是
     * {@code attempt_count + 1 > maxAttempts}，在同样的左到右求值下实际会「少给一次机会」，
     * 与它自己 Javadoc 写的「含第 maxAttempts 次」不符。该处<b>已在本轮一并改为与这里一致</b>
     * （审计 12-②(b) 同批修正），两侧口径现已对齐；两处都在真实 MySQL 8.4 上逐次验证过。</p>
     */
    @Update("""
            UPDATE contact_verification_code
            SET attempt_count = attempt_count + 1,
                used = IF(attempt_count > #{maxAttempts}, 1, used),
                used_time = IF(attempt_count > #{maxAttempts}, NOW(3), used_time)
            WHERE user_id = #{userId}
              AND contact_type = #{type}
              AND used = 0
              AND expire_time >= NOW(3)
            """)
    int bumpContactVerificationAttempt(@Param("userId") Long userId,
                                       @Param("type") String type,
                                       @Param("maxAttempts") int maxAttempts);

    /**
     * 校验第 2 步：仅当验证码<b>摘要</b>一致、未使用、未过期、且尝试次数未超上限时消费成功。
     *
     * <p>比对仍在 SQL 层一次完成，故防爆破的原子性不变（见 VerifyCodeHasher 类注释）。
     * 入参是提交码经 {@code VerifyCodeHasher} 算出的 64 位十六进制摘要。</p>
     *
     * @return 消费成功返回 1，否则返回 0
     */
    @Update("""
            UPDATE contact_verification_code
            SET used = 1,
                used_time = NOW(3)
            WHERE user_id = #{userId}
              AND contact_type = #{type}
              AND code = #{codeHash}
              AND used = 0
              AND attempt_count <= #{maxAttempts}
              AND expire_time >= NOW(3)
            """)
    int consumeContactVerificationCode(@Param("userId") Long userId,
                                       @Param("type") String type,
                                       @Param("codeHash") String codeHash,
                                       @Param("maxAttempts") int maxAttempts);

    /** 仅用于把「尝试次数过多」与「验证码无效」区分开（对齐 email 侧的 findAttemptCount）。 */
    @Select("SELECT attempt_count FROM contact_verification_code WHERE user_id = #{userId} AND contact_type = #{type} LIMIT 1")
    Integer findContactVerificationAttempt(@Param("userId") Long userId,
                                           @Param("type") String type);

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
