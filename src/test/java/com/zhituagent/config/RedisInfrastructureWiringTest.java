package com.zhituagent.config;

import com.zhituagent.ZhituAgentApplication;
import com.zhituagent.memory.MemoryStore;
import com.zhituagent.session.SessionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = {ZhituAgentApplication.class, RedisInfrastructureWiringTest.RedisInfraTestConfig.class},
        properties = {
                "zhitu.infrastructure.redis-enabled=true"
        }
)
class RedisInfrastructureWiringTest {

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private MemoryStore memoryStore;

    @Test
    void shouldUseRedisInfrastructureBeansWhenRedisIsEnabled() {
        assertThat(sessionRepository.getClass().getSimpleName()).isEqualTo("RedisSessionRepository");
        assertThat(memoryStore.getClass().getSimpleName()).isEqualTo("RedisMemoryStore");
    }

    @TestConfiguration
    static class RedisInfraTestConfig {

        @Bean
        @Primary
        StringRedisTemplate stringRedisTemplate() {
            return Mockito.mock(StringRedisTemplate.class);
        }
    }
}
