package uno.acloud.common.permission;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresTeamPermission {
    String value();
    String teamIdArg() default "teamId";
    boolean skipWhenTeamIdMissing() default true;
}
