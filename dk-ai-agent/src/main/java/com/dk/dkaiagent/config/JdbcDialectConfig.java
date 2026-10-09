package com.dk.dkaiagent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
public class JdbcDialectConfig {
    @Bean
    @Profile("local-desktop")
    public JdbcDialect localDesktopJdbcDialect() { return JdbcDialect.H2; }

    @Bean
    @Profile("!local-desktop")
    public JdbcDialect serverJdbcDialect() { return JdbcDialect.POSTGRESQL; }
}
