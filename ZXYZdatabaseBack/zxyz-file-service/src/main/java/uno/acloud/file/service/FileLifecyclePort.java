package uno.acloud.file.service;

import java.util.List;

public interface FileLifecyclePort {

    int logicalDelete(List<Long> fileIds, Long userId);

    int reallyDelete(List<Long> fileIds, Long userId);

    int restoreFiles(List<Long> fileIds, long userId);
}
