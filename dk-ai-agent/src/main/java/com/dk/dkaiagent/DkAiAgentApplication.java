package com.dk.dkaiagent;

import com.dk.dkaiagent.config.DeploymentGuard;
import com.dk.dkaiagent.config.DeploymentProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DkAiAgentApplication {

    public static void main(String[] args) {
        DeploymentProperties deployment = DeploymentGuard.validateEnvironment(System.getenv());
        SpringApplication application = new SpringApplication(DkAiAgentApplication.class);
        application.addListeners(new DeploymentGuard(deployment));
        application.run(args);
    }

}
