package com.liming.rag.persistence;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusPropertiesCustomizer;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 的全局配置（D-053）：SQL 只写在各模块的 {@code mapper/<模块名>/*.xml} 里，不用注解（AGENTS.md“约束”）；
 * 单表分页用 {@code selectPage}，由分页插件补上 {@code count} 和 {@code LIMIT/OFFSET}，多表分页仍写在 XML 里。
 */
@Configuration
class MybatisPlusConfiguration {

    static final String MAPPER_LOCATIONS = "classpath*:/mapper/**/*.xml";

    @Bean
    MybatisPlusPropertiesCustomizer mapperLocations() {
        return properties -> {
            properties.setMapperLocations(new String[] {MAPPER_LOCATIONS});
            properties.getGlobalConfig().setBanner(false);
        };
    }

    @Bean
    MybatisPlusInterceptor mybatisPlusInterceptor() {
        var interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        return interceptor;
    }
}
