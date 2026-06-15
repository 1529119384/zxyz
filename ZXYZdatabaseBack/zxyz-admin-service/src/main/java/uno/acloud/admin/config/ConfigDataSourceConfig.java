package uno.acloud.admin.config;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
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
