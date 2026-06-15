package uno.acloud.file.storage;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * 存储提供者接口
 * <p>
 * 定义统一的文件存储操作，支持多种后端实现（OSS、本地磁盘等）。
 * </p>
 */
public interface StorageProvider {

    /**
     * 提供者唯一标识
     *
     * @return 如 "oss", "local"
     */
    String providerId();

    /**
     * 提供者显示名称
     *
     * @return 如 "阿里云 OSS", "本地磁盘"
     */
    String displayName();

    /**
     * 是否支持预签名直传上传
     *
     * @return OSS: true, 本地磁盘: false
     */
    boolean supportsPresignedUpload();

    /**
     * 是否支持预签名直传下载
     *
     * @return OSS: true, 本地磁盘: false
     */
    boolean supportsPresignedDownload();

    /**
     * 生成上传信息
     *
     * @param objectKey         对象键
     * @param originalName      原始文件名
     * @param contentType       MIME 类型
     * @param contentDisposition Content-Disposition 头
     * @return 上传信息
     */
    UploadInfo generateUploadInfo(String objectKey, String originalName,
                                  String contentType, String contentDisposition);

    /**
     * 生成下载信息
     *
     * @param objectKey    对象键
     * @param originalName 原始文件名
     * @return 下载信息
     */
    DownloadInfo generateDownloadInfo(String objectKey, String originalName);

    /**
     * 接收文件上传（非预签名提供者使用）
     *
     * @param objectKey         对象键
     * @param inputStream       输入流
     * @param contentType       MIME 类型
     * @param contentDisposition Content-Disposition 头
     * @return 写入字节数
     */
    long receiveUpload(String objectKey, InputStream inputStream,
                       String contentType, String contentDisposition);

    /**
     * 流式输出文件（非预签名提供者使用）
     *
     * @param objectKey    对象键
     * @param outputStream 输出流
     */
    void streamDownload(String objectKey, OutputStream outputStream);

    /**
     * 检查对象是否存在
     *
     * @param objectKey 对象键
     * @return 是否存在
     */
    boolean objectExists(String objectKey);

    /**
     * 获取对象大小（字节）
     *
     * @param objectKey 对象键
     * @return 对象大小，不存在返回 null
     */
    Long getObjectSize(String objectKey);

    /**
     * 删除单个对象
     *
     * @param objectKey 对象键
     */
    void deleteObject(String objectKey);

    /**
     * 批量删除对象
     *
     * @param objectKeys 对象键列表
     */
    void deleteObjects(List<String> objectKeys);

    /**
     * 更新 Content-Disposition 元数据（重命名时使用）
     *
     * @param objectKey    对象键
     * @param originalName 新的原始文件名
     */
    void updateContentDisposition(String objectKey, String originalName);
}
