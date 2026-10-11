package uno.acloud.project.web;

import uno.acloud.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * P2-4 探针控制器（{@code MvcLocalDateTimeFormatTest} 专用）：
 * 返回含 LocalDateTime 的 VO（无 @JsonFormat 字段级注解，只受全局 customizer 影响）。
 * 刻意做成顶层类并放进测试包 {@code uno.acloud.project.web} ——
 * 由切片主配置 {@code MvcLocalDateTimeSliceApp} 的组件扫描注册，
 * 不牵入 project 的任何真实 Controller / Service / Mapper。
 */
@RestController
public class ProbeController {

    @GetMapping("/probe/local-date-time")
    public Result<ProbeVO> get() {
        return Result.of(new ProbeVO(MvcLocalDateTimeFormatTest.SAMPLE));
    }

    /** 探针 VO：字段名 value，无任何 Jackson 注解。 */
    public record ProbeVO(java.time.LocalDateTime value) {
    }
}
