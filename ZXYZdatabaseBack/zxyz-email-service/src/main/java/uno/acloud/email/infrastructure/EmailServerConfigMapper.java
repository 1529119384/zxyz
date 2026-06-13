package uno.acloud.email.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import uno.acloud.email.domain.EmailServerConfig;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface EmailServerConfigMapper extends BaseMapper<EmailServerConfig> {

    @Update("""
            UPDATE email_server_config
            SET config_name = #{configName},
                host = #{host},
                port = #{port},
                username = #{username},
                password_cipher = #{passwordCipher},
                from_address = #{fromAddress},
                transport_strategy = #{transportStrategy},
                update_time = NOW(3)
            WHERE id = #{id}
            """)
    int update(EmailServerConfig config);

    @Select("""
            SELECT COUNT(*)
            FROM email_server_config
            """)
    int countAll();

    @Select("""
            SELECT id, config_name, host, port, username, password_cipher, from_address,
                   transport_strategy, active, last_test_status, last_test_time, last_test_message,
                   create_time, update_time
            FROM email_server_config
            WHERE active = 1
            ORDER BY update_time DESC, id DESC
            LIMIT 1
            """)
    EmailServerConfig getActive();

    @Select("""
            SELECT id, config_name, host, port, username, password_cipher, from_address,
                   transport_strategy, active, last_test_status, last_test_time, last_test_message,
                   create_time, update_time
            FROM email_server_config
            ORDER BY active DESC, update_time DESC, id DESC
            """)
    List<EmailServerConfig> listAll();

    @Update("""
            UPDATE email_server_config
            SET active = 0,
                update_time = NOW(3)
            WHERE active = 1
            """)
    int deactivateAll();

    @Update("""
            UPDATE email_server_config
            SET active = 1,
                update_time = NOW(3)
            WHERE id = #{id}
            """)
    int activate(@Param("id") Long id);

    @Update("""
            UPDATE email_server_config
            SET last_test_status = #{status},
                last_test_time = #{testTime},
                last_test_message = #{message},
                update_time = NOW(3)
            WHERE id = #{id}
            """)
    int updateLastTest(@Param("id") Long id,
                       @Param("status") String status,
                       @Param("testTime") LocalDateTime testTime,
                       @Param("message") String message);
}
