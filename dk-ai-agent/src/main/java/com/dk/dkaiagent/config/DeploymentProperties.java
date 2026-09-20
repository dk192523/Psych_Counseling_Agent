package com.dk.dkaiagent.config;

/** Validated deployment declaration; instanceId is an observation label, never a lock. */
public record DeploymentProperties(String mode, int replicaCount, String instanceId) {
}
