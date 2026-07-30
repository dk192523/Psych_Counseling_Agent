package com.dk.dkaiagent.account;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class AccountSecurityBeans {

    /**
     * 冻结合约 AUTH-v1：BCrypt strength 默认 10。
     * Wave2 的 SecurityConfig 直接复用本 Bean，不得再定义第二个 PasswordEncoder。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }
}
