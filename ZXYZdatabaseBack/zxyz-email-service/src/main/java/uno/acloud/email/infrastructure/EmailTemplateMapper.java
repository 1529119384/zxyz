package uno.acloud.email.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import uno.acloud.email.domain.EmailTemplate;

@Mapper
public interface EmailTemplateMapper extends BaseMapper<EmailTemplate> {

    @Select("""
            SELECT id, template_code, subject_template, content_html, status, create_time, update_time
            FROM email_template
            WHERE template_code = #{templateCode}
              AND status = 0
            LIMIT 1
            """)
    EmailTemplate getActiveByCode(@Param("templateCode") String templateCode);
}
