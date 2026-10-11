package uno.acloud.im.convert;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import uno.acloud.im.infrastructure.persistence.entity.ImConversation;
import uno.acloud.im.infrastructure.persistence.entity.TeamInvitation;
import uno.acloud.im.infrastructure.persistence.entity.UserPresence;
import uno.acloud.im.vo.ProjectConversationVO;
import uno.acloud.im.vo.TeamInvitationVO;
import uno.acloud.im.vo.UserPresenceVO;

/**
 * MapStruct 对象映射 — im-service 实体到 VO 的转换。
 *
 * <p>本接口由 MapStruct 注解处理器在编译期自动生成实现类（ImEntityMapperImpl）。
 * Spring 通过 @Mapper(componentModel = "spring") 自动注册为 Bean。</p>
 *
 * <p>渐进迁移策略：新代码优先使用 MapStruct，旧的手动转换逐步替换。</p>
 *
 * <p>🔴-2（ISSUE/54 §2.4，46 号 P1-2 收口）：本接口原先放在
 * {@code uno.acloud.im.infrastructure.mapper} —— 与 MyBatis @MapperScan("uno.acloud.im.infrastructure.mapper")
 * <b>同包</b>。MyBatis 的 mapper 扫描器不区分接口来源，会把 MapStruct 生成/待生成的接口一并注册成
 * MyBatis mapper（同包劫持），运行期 Bean 覆盖胜者未定、随时可能炸。
 * 迁到独立包 {@code uno.acloud.im.convert}（项目惯例，与 MapStruct 勿与 @MapperScan 同包的红线对齐），
 * @MapperScan 保持不变。</p>
 */
@Mapper(componentModel = "spring")
public interface ImEntityMapper {

    TeamInvitationVO toInvitationVO(TeamInvitation invitation);

    @Mapping(target = "conversationId", source = "id")
    @Mapping(target = "readOnly", expression = "java(Boolean.TRUE.equals(conversation.getReadOnly()))")
    ProjectConversationVO toConversationVO(ImConversation conversation);

    @Mapping(target = "online", expression = "java(Boolean.TRUE.equals(presence.getOnline()))")
    @Mapping(target = "connectionCount", expression = "java(presence.getConnectionCount() == null ? 0 : presence.getConnectionCount())")
    UserPresenceVO toPresenceVO(UserPresence presence);
}
