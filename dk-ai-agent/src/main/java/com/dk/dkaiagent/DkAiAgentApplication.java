package com.dk.dkaiagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DkAiAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(DkAiAgentApplication.class, args);
    }

}
