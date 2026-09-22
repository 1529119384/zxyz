package uno.acloud.admin.config;

import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * 配置管理数据源配置
 * <p>使用 {@code config.datasource.*} 前缀，避免与 Spring Boot 自动配置的
 * {@code spring.datasource} 冲突。</p>
 *
 * <p><b>⚠️ 池参数必须直接挂在 {@code config.datasource.*} 下</b>：{@link DataSourceBuilder}
 * 对 MySQL 产出的是 {@code HikariDataSource}，它把 {@code maximumPoolSize} / {@code minimumIdle}
 * 暴露为直接属性，没有 {@code hikari} 这一层。因此 yml 必须写成
 * {@code config.datasource.maximum-pool-size} / {@code config.datasource.minimum-idle}，
 * 而不能写成 {@code config.datasource.hikari.maximum-pool-size}——后者没有绑定目标，
 * 又因 {@code @ConfigurationProperties} 的 {@code ignoreUnknownFields} 默认 {@code true}
 * 而被静默忽略，池会悄悄跑在 Hikari 默认值 10/10 上（审计 C-16）。</p>
 * <p>该约束由 {@code ConfigDataSourcePoolBindingTest} 守护（先红后绿）。</p>
 */
@Configuration
public class ConfigDataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("config.datasource")
    public DataSource configDataSource() {
        return DataSourceBuilder.create().build();
    }

    @Bean
    @Primary
    public SqlSessionFactory configSqlSessionFactory(@Qualifier("configDataSource") DataSource dataSource) throws Exception {
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        return factory.getObject();
    }

    @Bean
    @Primary
    public SqlSessionTemplate configSqlSessionTemplate(@Qualifier("configSqlSessionFactory") SqlSessionFactory sqlSessionFactory) {
        return new SqlSessionTemplate(sqlSessionFactory);
    }

    @Bean
    @Primary
    public PlatformTransactionManager configTransactionManager(@Qualifier("configDataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
