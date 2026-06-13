package uno.acloud.im.infrastructure.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import uno.acloud.im.domain.model.ImConversation;
import uno.acloud.im.domain.model.TeamInvitation;
import uno.acloud.im.domain.model.UserPresence;
import uno.acloud.im.vo.ProjectConversationVO;
import uno.acloud.im.vo.TeamInvitationVO;
import uno.acloud.im.vo.UserPresenceVO;

import java.util.List;

/**
 * MapStruct 对象映射 — im-service 实体到 VO 的转换。
 *
 * <p>本接口由 MapStruct 注解处理器在编译期自动生成实现类（ImEntityMapperImpl）。
 * Spring 通过 @Mapper(componentModel = "spring") 自动注册为 Bean。</p>
 *
 * <p>渐进迁移策略：新代码优先使用 MapStruct，旧的手动转换逐步替换。</p>
 */
@Mapper(componentModel = "spring")
public interface ImEntityMapper {

    TeamInvitationVO toInvitationVO(TeamInvitation invitation);

    List<TeamInvitationVO> toInvitationVOList(List<TeamInvitation> invitations);

    @Mapping(target = "conversationId", source = "id")
    @Mapping(target = "readOnly", expression = "java(Boolean.TRUE.equals(conversation.getReadOnly()))")
    ProjectConversationVO toConversationVO(ImConversation conversation);

    List<ProjectConversationVO> toConversationVOList(List<ImConversation> conversations);

    @Mapping(target = "online", expression = "java(Boolean.TRUE.equals(presence.getOnline()))")
    @Mapping(target = "connectionCount", expression = "java(presence.getConnectionCount() == null ? 0 : presence.getConnectionCount())")
    UserPresenceVO toPresenceVO(UserPresence presence);
}
