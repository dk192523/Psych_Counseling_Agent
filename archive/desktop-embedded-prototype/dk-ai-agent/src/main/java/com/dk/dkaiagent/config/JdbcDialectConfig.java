package com.dk.dkaiagent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Select SQL syntax without opening a connection or probing a repository's JdbcTemplate. */
@Configuration
public class JdbcDialectConfig {
    @Bean
    @Profile("desktop")
    public JdbcDialect desktopJdbcDialect() {
        return JdbcDialect.H2;
    }

    @Bean
    @Profile("!desktop")
    public JdbcDialect serverJdbcDialect() {
        return JdbcDialect.POSTGRESQL;
    }
}
