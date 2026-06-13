package uno.acloud.file.service.impl;

import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.file.infrastructure.entity.FileNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class FilePathUtil {

    private FilePathUtil() {
    }

    public static List<FileNode> reduceToTopLevelNodes(List<? extends FileNode> fileNodes) {
        List<FileNode> sortedNodes = new ArrayList<>(fileNodes);
        sortedNodes.sort(Comparator.comparingInt(file -> safeStorePath(file.getStorePath()).length()));

        List<FileNode> topLevelNodes = new ArrayList<>();
        for (FileNode candidate : sortedNodes) {
            String candidatePath = safeStorePath(candidate.getStorePath());
            boolean hasSelectedAncestor = topLevelNodes.stream()
                    .anyMatch(parent -> isDescendantPath(candidatePath, safeStorePath(parent.getStorePath())));
            if (!hasSelectedAncestor) {
                topLevelNodes.add(candidate);
            }
        }
        return topLevelNodes;
    }

    public static boolean isDescendantPath(String candidatePath, String ancestorPath) {
        return candidatePath.startsWith(ancestorPath + "/");
    }

    public static String normalizeStorePathSegment(String rawPath) {
        String normalizedPath = rawPath.replace('\\', '/').replaceAll("/+", "/");
        if (!normalizedPath.startsWith("/")) {
            normalizedPath = "/" + normalizedPath;
        }
        if (normalizedPath.length() > 1 && normalizedPath.endsWith("/")) {
            normalizedPath = normalizedPath.substring(0, normalizedPath.length() - 1);
        }
        return normalizedPath;
    }

    public static String safeStorePath(String storePath) {
        if (storePath == null || storePath.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件路径无效");
        }
        return storePath;
    }
}
