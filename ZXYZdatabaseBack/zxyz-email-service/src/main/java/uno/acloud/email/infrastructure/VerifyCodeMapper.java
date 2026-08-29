package uno.acloud.email.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import uno.acloud.email.domain.VerifyCode;

@Mapper
public interface VerifyCodeMapper extends BaseMapper<VerifyCode> {

    @Insert("""
            INSERT INTO verify_code(
                email, scene, code, expire_time, used, used_time, request_ip, email_record_id, attempt_count, create_time, update_time
            )
            VALUES(
                #{email}, #{scene}, #{code}, #{expireTime}, 0, NULL, #{requestIp}, #{emailRecordId}, 0, #{createTime}, #{updateTime}
            )
            ON DUPLICATE KEY UPDATE
                code = VALUES(code),
                expire_time = VALUES(expire_time),
                used = 0,
                used_time = NULL,
                request_ip = VALUES(request_ip),
                email_record_id = VALUES(email_record_id),
                attempt_count = 0,
                update_time = VALUES(update_time)
            """)
    int upsert(VerifyCode verifyCode);

    /**
     * 在保证行仍"存活"(存在、未使用、未过期)的前提下，对验证码尝试计数加 1。
     * 仅当累计尝试次数**超过**上限（第 maxAttempts+1 次）时才作废该码（置 used=1）；
     * 第 maxAttempts 次本身仍保留 used=0，从而允许用户在最后一次机会输对成功。
     *
     * @return 命中存活行返回 1，无匹配行（已使用/已过期/不存在）返回 0
     */
    @Update("""
            UPDATE verify_code
            SET attempt_count = attempt_count + 1,
                used = IF(attempt_count + 1 > #{maxAttempts}, 1, used),
                used_time = IF(attempt_count + 1 > #{maxAttempts}, NOW(3), used_time),
                update_time = NOW(3)
            WHERE email = #{email}
              AND scene = #{scene}
              AND used = 0
              AND expire_time >= NOW(3)
            """)
    int bumpAttemptCount(@Param("email") String email,
                         @Param("scene") String scene,
                         @Param("maxAttempts") int maxAttempts);

    /**
     * 仅当验证码正确、未使用、未过期且尝试次数未**超过**上限(maxAttempts 内,
     * 含第 maxAttempts 次)时消费成功（置 used=1）。
     *
     * @return 消费成功返回 1，否则返回 0
     */
    @Update("""
            UPDATE verify_code
            SET used = 1,
                used_time = NOW(3),
                update_time = NOW(3)
            WHERE email = #{email}
              AND scene = #{scene}
              AND code = #{code}
              AND used = 0
              AND attempt_count <= #{maxAttempts}
              AND expire_time >= NOW(3)
            """)
    int markUsedByCode(@Param("email") String email,
                       @Param("scene") String scene,
                       @Param("code") String code,
                       @Param("maxAttempts") int maxAttempts);

    @Select("SELECT attempt_count FROM verify_code WHERE email = #{email} AND scene = #{scene} LIMIT 1")
    Integer findAttemptCount(@Param("email") String email,
                             @Param("scene") String scene);

    @Delete("DELETE FROM verify_code WHERE expire_time < NOW()")
    int deleteExpired();
}
