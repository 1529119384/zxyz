package uno.acloud.email.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import uno.acloud.email.domain.VerifyCode;

@Mapper
public interface VerifyCodeMapper extends BaseMapper<VerifyCode> {

    @Insert("""
            INSERT INTO verify_code(
                email, scene, code, expire_time, used, used_time, request_ip, email_record_id, create_time, update_time
            )
            VALUES(
                #{email}, #{scene}, #{code}, #{expireTime}, 0, NULL, #{requestIp}, #{emailRecordId}, #{createTime}, #{updateTime}
            )
            ON DUPLICATE KEY UPDATE
                code = VALUES(code),
                expire_time = VALUES(expire_time),
                used = 0,
                used_time = NULL,
                request_ip = VALUES(request_ip),
                email_record_id = VALUES(email_record_id),
                update_time = VALUES(update_time)
            """)
    int upsert(VerifyCode verifyCode);

    @Update("""
            UPDATE verify_code
            SET used = 1,
                used_time = NOW(3),
                update_time = NOW(3)
            WHERE email = #{email}
              AND scene = #{scene}
              AND code = #{code}
              AND used = 0
              AND expire_time >= NOW(3)
            """)
    int markUsedByCode(@Param("email") String email,
                       @Param("scene") String scene,
                       @Param("code") String code);

    @Delete("DELETE FROM verify_code WHERE expire_time < NOW()")
    int deleteExpired();
}
