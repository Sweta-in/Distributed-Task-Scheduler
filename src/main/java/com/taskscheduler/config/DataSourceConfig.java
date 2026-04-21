package com.taskscheduler.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * DataSource configuration.
 * <p>
 * The actual DataSource and HikariCP connection pool are configured via application.yml
 * (spring.datasource.*). This class enables JPA repositories and transaction management.
 * </p>
 */
@Configuration
@EnableJpaRepositories(basePackages = "com.taskscheduler.repository")
@EnableTransactionManagement
public class DataSourceConfig {
    // HikariCP DataSource is auto-configured by Spring Boot from application.yml properties.
    // No additional bean definitions are needed.
}
