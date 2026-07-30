package com.dk.dkaiagent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class Java25ConcurrencyConfig {

    @Bean(name = "agentVirtualThreadExecutor", destroyMethod = "close")
    ExecutorService agentVirtualThreadExecutor() {
        return Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("counseling-agent-", 0).factory());
    }

    @Bean(name = "agentVirtualThreadScheduler", destroyMethod = "dispose")
    Scheduler agentVirtualThreadScheduler(ExecutorService agentVirtualThreadExecutor) {
        return Schedulers.fromExecutor(agentVirtualThreadExecutor);
    }
}
