package uno.acloud.file.infrastructure.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import uno.acloud.file.infrastructure.entity.FileNode;
import uno.acloud.file.infrastructure.entity.Folder;
import uno.acloud.file.vo.FolderCreateResultVO;
import uno.acloud.file.vo.RenameFileVO;

/**
 * MapStruct 对象映射 — file-service 实体到 VO 的转换。
 *
 * <p>本接口由 MapStruct 注解处理器在编译期自动生成实现类（FileEntityMapperImpl）。
 * Spring 通过 @Mapper(componentModel = "spring") 自动注册为 Bean。</p>
 *
 * <p>注意：FileConverter 中的 toFileListItemVO / toFileInfoDTO / toFileResourceVO
 * 依赖 FileItem 子类的运行时类型检查（FileNode instanceof FileItem），
 * 涉及多态字段（category、fileSize）的安全提取，且目标 VO 有歧义构造函数，
 * 不适合用 MapStruct 映射，仍保留在 FileConverter 中手动实现。</p>
 */
@Mapper(componentModel = "spring")
public interface FileEntityMapper {

    // ==================== Folder → FolderCreateResultVO ====================

    /** 文件夹创建结果 VO */
    @Mapping(target = "id", source = "id")
    @Mapping(target = "originalName", source = "originalName")
    @Mapping(target = "fileType", source = "fileType")
    @Mapping(target = "parentId", source = "parentId")
    FolderCreateResultVO toFolderCreateResultVO(Folder folder);

    // ==================== FileNode → RenameFileVO ====================

    /** 文件重命名结果 VO。modifyTime 由调用方在映射后手动设置。 */
    @Mapping(target = "modifyTime", ignore = true)
    RenameFileVO toRenameFileVO(FileNode fileNode);
}
