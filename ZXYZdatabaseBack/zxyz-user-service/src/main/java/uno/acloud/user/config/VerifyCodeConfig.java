package uno.acloud.user.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uno.acloud.common.util.VerifyCodeHasher;

/**
 * 验证码摘要器装配。
 *
 * <p>pepper 缺失时 {@link VerifyCodeHasher} 构造器会抛异常 ⇒ 服务启动失败。这是有意为之：
 * 没有 pepper 就无法生成摘要，验码功能本来就是坏的；此时「退化成不加密/明文校验」是不可接受
 * 的静默弱配置，宁可响亮地起不来。算法与语义详见 VerifyCodeHasher 类注释。</p>
 */
@Configuration
public class VerifyCodeConfig {

    @Bean
    VerifyCodeHasher verifyCodeHasher(ServiceProperties serviceProperties) {
        return new VerifyCodeHasher(serviceProperties.getVerification().getCodePepper(),
                serviceProperties.getVerification().isAllowInsecurePepper());
    }
}
