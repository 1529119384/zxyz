package uno.acloud.common.util;

import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 批量权限操作辅助工具
 * <p>
 * 消除权限分配中的 N+1 查询：将循环逐条查询 permission_id + 逐条 INSERT
 * 替换为批量 SELECT + 批量 INSERT。
 * <p>
 * 使用方式：
 * <pre>
 * // 1. 批量解析 permission codes → IDs
 * List&lt;Integer&gt; ids = BatchPermissionHelper.resolvePermissionIds(
 *     permissionCodes,
 *     mapper::getPermissionIdsByCodes   // List&lt;T&gt; getPermissionIdsByCodes(List&lt;String&gt; codes)
 * );
 *
 * // 2. 批量写入关联关系
 * batchInsert.apply(ids);
 * </pre>
 */
public final class BatchPermissionHelper {

    private BatchPermissionHelper() {
    }

    /**
     * 批量将 permission code 列表解析为 ID 列表。
     *
     * @param permissionCodes       需要解析的权限码列表（不可为 null）
     * @param batchLookupFunction   批量查询函数，接受 List&lt;String&gt; codes 返回包含 id 和 code 的对象列表
     * @param idExtractor           从查询结果中提取 id 的函数
     * @param codeExtractor         从查询结果中提取 code 的函数
     * @return 按输入顺序排列的 permission ID 列表
     * @throws BusinessException 如果任何一个 code 在数据库中不存在
     */
    public static <T> List<Integer> resolvePermissionIds(
            List<String> permissionCodes,
            Function<List<String>, List<T>> batchLookupFunction,
            Function<T, Integer> idExtractor,
            Function<T, String> codeExtractor) {

        if (permissionCodes == null || permissionCodes.isEmpty()) {
            return List.of();
        }

        List<T> lookupResults = batchLookupFunction.apply(permissionCodes);
        Map<String, Integer> codeToId = lookupResults.stream()
                .collect(Collectors.toMap(codeExtractor, idExtractor, (a, b) -> a));

        return permissionCodes.stream()
                .map(code -> {
                    Integer id = codeToId.get(code);
                    if (id == null) {
                        throw new BusinessException(ErrorCode.NOT_FOUND, "权限不存在: " + code);
                    }
                    return id;
                })
                .toList();
    }
}
