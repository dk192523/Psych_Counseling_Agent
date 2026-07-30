package com.dk.dkaiagent.security;

import com.dk.dkaiagent.security.dto.AuthUserDto;
import com.dk.dkaiagent.security.dto.MeDto;
import com.dk.dkaiagent.security.dto.StatsDto;
import com.dk.dkaiagent.security.dto.TempPasswordResponse;
import com.dk.dkaiagent.security.dto.UserDto;
import com.dk.dkaiagent.security.dto.UserPageResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * DTO 泄露防线测试（冻结合约 AUTH-v1）：任何出参 DTO 都不得携带 passwordHash/password_hash。
 * 用反射枚举 record 组件 + Jackson 序列化双重校验，防止后续改动悄悄把哈希带出 API 边界。
 */
class DtoLeakGuardTest {

    // 用 Spring 的构建器镜像真实 API 的序列化配置（注册 JSR-310 等模块），避免 Instant 序列化失真。
    private static final ObjectMapper MAPPER = Jackson2ObjectMapperBuilder.json().build();

    private static void assertNoPasswordLeak(Class<?> dtoType) {
        for (RecordComponent component : dtoType.getRecordComponents()) {
            String name = component.getName().toLowerCase();
            assertFalse(name.contains("passwordhash") || name.contains("password_hash")
                            || name.equals("hash"),
                    dtoType.getSimpleName() + " exposes secret field: " + component.getName());
        }
    }

    @Test
    void outboundDtosCarryNoPasswordHashComponent() {
        assertNoPasswordLeak(UserDto.class);
        assertNoPasswordLeak(UserPageResponse.class);
        assertNoPasswordLeak(MeDto.class);
        assertNoPasswordLeak(AuthUserDto.class);
        assertNoPasswordLeak(StatsDto.class);
        assertNoPasswordLeak(TempPasswordResponse.class);
    }

    @Test
    void adminUserListSerializationOmitsPasswordHash() throws Exception {
        Instant now = Instant.parse("2026-07-24T08:00:00Z");
        UserDto row = new UserDto(1L, "alice", "USER", "ACTIVE", now, now, null, null, 3L);
        UserPageResponse response = new UserPageResponse(List.of(row), 1L, 0, 20);

        String json = MAPPER.writeValueAsString(response);

        assertFalse(json.toLowerCase().contains("passwordhash"));
        assertFalse(json.toLowerCase().contains("password_hash"));
    }

    @Test
    void meSerializationOmitsPasswordHash() throws Exception {
        Instant now = Instant.parse("2026-07-24T08:00:00Z");
        MeDto me = new MeDto(1L, "alice", "USER", "ACTIVE", now, now);

        String json = MAPPER.writeValueAsString(me);

        assertFalse(json.toLowerCase().contains("passwordhash"));
        assertFalse(json.toLowerCase().contains("password_hash"));
    }
}
