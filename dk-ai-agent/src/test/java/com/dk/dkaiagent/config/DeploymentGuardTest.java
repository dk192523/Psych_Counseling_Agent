package com.dk.dkaiagent.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeploymentGuardTest {

    @Test
    void emptyEnvironmentUsesSingleReplicaDefaultsAndGeneratesInstanceId() {
        DeploymentProperties properties = DeploymentGuard.validateEnvironment(Map.of());

        assertAll(
                () -> assertEquals("single", properties.mode()),
                () -> assertEquals(1, properties.replicaCount()),
                () -> assertNotNull(properties.instanceId()),
                () -> assertFalse(properties.instanceId().isBlank()),
                () -> assertTrue(properties.instanceId().matches("[A-Za-z0-9._:-]{1,128}")),
                () -> assertEquals(properties.instanceId(), UUID.fromString(properties.instanceId()).toString()));
    }

    @Test
    void acceptsExplicitValidInstanceId() {
        String instanceId = "Node_01.region:worker-2";

        DeploymentProperties properties = DeploymentGuard.validate("single", "1", instanceId);

        assertAll(
                () -> assertEquals("single", properties.mode()),
                () -> assertEquals(1, properties.replicaCount()),
                () -> assertEquals(instanceId, properties.instanceId()));
    }

    @Test
    void rejectsModesOtherThanSingle() {
        for (String mode : new String[] {"multi", "Single", "", " ", null}) {
            assertThrows(IllegalStateException.class,
                    () -> DeploymentGuard.validate(mode, "1", "node-1"),
                    "Expected rejection for mode: " + mode);
        }
    }

    @Test
    void rejectsReplicaCountsOtherThanOne() {
        for (String replicaCount : new String[] {"0", "2", "10"}) {
            assertThrows(IllegalStateException.class,
                    () -> DeploymentGuard.validate("single", replicaCount, "node-1"),
                    "Expected rejection for replica count: " + replicaCount);
        }
    }

    @Test
    void rejectsEmptyReplicaCounts() {
        for (String replicaCount : new String[] {"", " ", null}) {
            assertThrows(IllegalStateException.class,
                    () -> DeploymentGuard.validate("single", replicaCount, "node-1"),
                    "Expected rejection for empty replica count: " + replicaCount);
        }
    }

    @Test
    void rejectsNegativeReplicaCount() {
        assertThrows(IllegalStateException.class,
                () -> DeploymentGuard.validate("single", "-1", "node-1"));
    }

    @Test
    void rejectsMalformedReplicaCounts() {
        for (String replicaCount : new String[] {"abc", "1.0", "01", "+1", " 1 ", "2147483648"}) {
            assertThrows(IllegalStateException.class,
                    () -> DeploymentGuard.validate("single", replicaCount, "node-1"),
                    "Expected rejection for malformed replica count: " + replicaCount);
        }
    }

    @Test
    void blankInstanceIdsGenerateUuids() {
        for (String instanceId : new String[] {null, "", " ", "\t\r\n"}) {
            DeploymentProperties properties = DeploymentGuard.validate("single", "1", instanceId);

            assertNotNull(properties.instanceId());
            assertEquals(properties.instanceId(), UUID.fromString(properties.instanceId()).toString());
        }
    }

    @Test
    void rejectsInstanceIdsWithIllegalCharacters() {
        for (String instanceId : new String[] {"node/1", "node 1", "node@1", "节点", "node\n1"}) {
            assertThrows(IllegalStateException.class,
                    () -> DeploymentGuard.validate("single", "1", instanceId),
                    "Expected rejection for instance ID: " + instanceId);
        }
    }

    @Test
    void acceptsInstanceIdAtMaximumLength() {
        String instanceId = "a".repeat(128);

        assertEquals(instanceId, DeploymentGuard.validate("single", "1", instanceId).instanceId());
    }

    @Test
    void rejectsInstanceIdLongerThanMaximumLength() {
        assertThrows(IllegalStateException.class,
                () -> DeploymentGuard.validate("single", "1", "a".repeat(129)));
    }
}
