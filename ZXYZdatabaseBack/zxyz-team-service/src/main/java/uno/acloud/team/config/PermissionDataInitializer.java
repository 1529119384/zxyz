package uno.acloud.team.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import uno.acloud.common.SystemPermissionCodes;
import uno.acloud.common.SystemRoleCodes;
import uno.acloud.team.mapper.PermissionRoleMapper;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
public class PermissionDataInitializer implements CommandLineRunner {

    private final PermissionRoleMapper mapper;

    private static final Set<String> BASIC_PERMISSIONS = Set.of(
            SystemPermissionCodes.FILE_READ,
            SystemPermissionCodes.FILE_UPLOAD,
            SystemPermissionCodes.FILE_WRITE,
            SystemPermissionCodes.FILE_DELETE,
            SystemPermissionCodes.FOLDER_CREATE,
            SystemPermissionCodes.TRASH_READ,
            SystemPermissionCodes.SHARE_CREATE,
            SystemPermissionCodes.SHARE_READ
    );

    public PermissionDataInitializer(PermissionRoleMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void run(String... args) {
        List<String> allCodes = reflectPermissionCodes();
        int created = 0;
        for (String code : allCodes) {
            String name = codeToName(code);
            created += mapper.insertPermissionIgnore(name, code, null);
        }
        if (created > 0) {
            log.info("初始化系统权限：新增 {} 条", created);
        }

        var adminRole = mapper.getRoleByCode(SystemRoleCodes.SYSTEM_ADMIN);
        var userRole = mapper.getRoleByCode(SystemRoleCodes.SYSTEM_USER);
        Integer adminRoleId = adminRole != null ? adminRole.getId() : null;
        Integer userRoleId = userRole != null ? userRole.getId() : null;

        int linked = 0;
        for (String code : allCodes) {
            Integer permId = mapper.getPermissionIdByCode(code);
            if (permId == null) continue;
            if (adminRoleId != null) {
                linked += mapper.insertRolePermissionIgnore(adminRoleId, permId);
            }
            if (userRoleId != null && BASIC_PERMISSIONS.contains(code)) {
                linked += mapper.insertRolePermissionIgnore(userRoleId, permId);
            }
        }
        if (linked > 0) {
            log.info("初始化角色权限关联：新增 {} 条", linked);
        }
    }

    private List<String> reflectPermissionCodes() {
        List<String> codes = new ArrayList<>();
        for (var field : SystemPermissionCodes.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                try {
                    codes.add((String) field.get(null));
                } catch (IllegalAccessException e) {
                    log.warn("无法读取权限常量: {}", field.getName());
                }
            }
        }
        return codes;
    }

    private static String codeToName(String code) {
        return code.replace(":", " - ");
    }
}
