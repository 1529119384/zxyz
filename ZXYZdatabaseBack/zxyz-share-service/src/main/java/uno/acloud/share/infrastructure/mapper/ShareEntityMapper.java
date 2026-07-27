package uno.acloud.share.infrastructure.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import uno.acloud.share.infrastructure.entity.Share;
import uno.acloud.share.vo.ShareMyListItemVO;
import uno.acloud.share.vo.SharePublicInfoVO;

import java.util.List;

/**
 * MapStruct 对象映射 — share-service 实体到 VO 的转换。
 *
 * <p>本接口由 MapStruct 注解处理器在编译期自动生成实现类（ShareEntityMapperImpl）。
 * Spring 通过 @Mapper(componentModel = "spring") 自动注册为 Bean。</p>
 *
 * <p>注意：ShareViewMapper 中的 toShareFilesResponseItemVO 接受 ShareFileProjection + boolean
 * 双参数，涉及跨领域数据合并和 computed 字段（invalid、invalidText），
 * 不适合用 MapStruct 映射，仍保留在 ShareViewMapper 中手动实现。</p>
 */
@Mapper(componentModel = "spring")
public interface ShareEntityMapper {

    // ==================== Share → ShareMyListItemVO ====================

    /**
     * Share 实体 → 我的分享列表项 VO。
     * shareUrl、hasPassword、expireType、statusText 由调用方在映射后手动设置
     * （依赖业务逻辑计算：密码非空判断、过期类型推导、状态文本等）。
     */
    @Mapping(target = "shareId", source = "id")
    @Mapping(target = "shareUrl", ignore = true)
    @Mapping(target = "hasPassword", ignore = true)
    @Mapping(target = "expireType", ignore = true)
    @Mapping(target = "statusText", ignore = true)
    ShareMyListItemVO toShareMyListItemVO(Share share);

    List<ShareMyListItemVO> toShareMyListItemVOList(List<Share> shares);

    // ==================== Share → SharePublicInfoVO ====================

    /**
     * Share 实体 → 公开分享信息 VO。
     * showUsername、needPassword、passed、canViewContent、statusText
     * 由调用方在映射后手动设置（依赖密码校验、访问令牌等运行时状态）。
     */
    @Mapping(target = "showUsername", ignore = true)
    @Mapping(target = "needPassword", ignore = true)
    @Mapping(target = "passed", ignore = true)
    @Mapping(target = "canViewContent", ignore = true)
    @Mapping(target = "statusText", ignore = true)
    SharePublicInfoVO toSharePublicInfoVO(Share share);
}
