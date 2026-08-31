package uno.acloud.email.convert;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import uno.acloud.email.domain.EmailRecord;
import uno.acloud.email.domain.EmailServerConfig;
import uno.acloud.email.vo.EmailRecordVO;
import uno.acloud.email.vo.EmailServerConfigVO;

import java.util.List;

/**
 * MapStruct 对象映射 — Email 领域实体到 VO 的转换。
 *
 * <p>替代 EmailServerConfigService.toVO() 和 EmailRecordQueryService.toVO()
 * 中的手动字段拷贝。</p>
 */
@Mapper(componentModel = "spring")
public interface EmailEntityMapper {

    /**
     * EmailServerConfig → EmailServerConfigVO。
     * passwordSet 由 passwordCipher 是否非空推导而来，passwordCipher 本身不暴露给 VO。
     * active 为 null 时按 false 处理（与原始 Boolean.TRUE.equals 语义一致）。
     */
    @Mapping(target = "passwordSet", expression = "java(config.getPasswordCipher() != null && !config.getPasswordCipher().isBlank())")
    @Mapping(target = "active", expression = "java(config.isEnabled())")
    EmailServerConfigVO toServerConfigVO(EmailServerConfig config);

    List<EmailServerConfigVO> toServerConfigVOList(List<EmailServerConfig> configs);

    /**
     * EmailRecord → EmailRecordVO。所有字段 1:1 映射。
     */
    EmailRecordVO toRecordVO(EmailRecord record);

    List<EmailRecordVO> toRecordVOList(List<EmailRecord> records);
}
