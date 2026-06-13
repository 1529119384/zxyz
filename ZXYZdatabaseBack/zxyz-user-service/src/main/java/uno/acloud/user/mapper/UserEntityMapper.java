package uno.acloud.user.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import uno.acloud.user.entity.User;
import uno.acloud.user.entity.UserQuota;
import uno.acloud.user.vo.LinkedAccountVO;
import uno.acloud.user.vo.UserInfoVO;
import uno.acloud.user.vo.UserQuotaVO;
import uno.acloud.user.vo.UserSearchItemVO;

import java.util.List;

/**
 * MapStruct 对象映射 — user-service 实体到 VO 的转换。
 *
 * <p>本接口由 MapStruct 注解处理器在编译期自动生成实现类（UserEntityMapperImpl）。
 * Spring 通过 @Mapper(componentModel = "spring") 自动注册为 Bean。</p>
 *
 * <p>注意：CurrentUserVO 依赖 Sa-Token 会话中的 roles/permissions，
 * 不适合用 MapStruct 映射，仍保留在 UserQueryHelper 中手动构建。</p>
 */
@Mapper(componentModel = "spring")
public interface UserEntityMapper {

    // ==================== User → UserSearchItemVO ====================

    /** 用户搜索结果项（id, username, name, avatar） */
    UserSearchItemVO toUserSearchItemVO(User user);

    List<UserSearchItemVO> toUserSearchItemVOList(List<User> users);

    // ==================== User → UserInfoVO ====================

    /**
     * 用户信息 VO（内部接口），null 字段安全转为空字符串。
     * username、name、email、avatar 为 null 时默认空字符串。
     */
    @Mapping(target = "username", expression = "java(user.getUsername() != null ? user.getUsername() : \"\")")
    @Mapping(target = "name", expression = "java(user.getName() != null ? user.getName() : \"\")")
    @Mapping(target = "email", expression = "java(user.getEmail() != null ? user.getEmail() : \"\")")
    @Mapping(target = "avatar", expression = "java(user.getAvatar() != null ? user.getAvatar() : \"\")")
    UserInfoVO toUserInfoVO(User user);

    List<UserInfoVO> toUserInfoVOList(List<User> users);

    // ==================== User → LinkedAccountVO ====================

    /**
     * 关联账号 VO。
     * trusted 字段需要额外查询 account_switch_trust 表，由调用方在映射后手动设置。
     */
    @Mapping(target = "trusted", ignore = true)
    LinkedAccountVO toLinkedAccountVO(User user);

    List<LinkedAccountVO> toLinkedAccountVOList(List<User> users);

    // ==================== UserQuota → UserQuotaVO ====================

    /**
     * 用户配额 VO。
     * storageLimit 为 null 时默认 0（与原始手动转换语义一致）。
     */
    @Mapping(target = "userId", source = "userId")
    @Mapping(target = "storageLimit", expression = "java(quota.getStorageLimit() != null ? quota.getStorageLimit() : 0)")
    UserQuotaVO toUserQuotaVO(UserQuota quota);
}
