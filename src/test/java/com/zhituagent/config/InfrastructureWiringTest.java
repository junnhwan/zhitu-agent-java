package com.zhituagent.config;

import com.zhituagent.ZhituAgentApplication;
import com.zhituagent.memory.MemoryStore;
import com.zhituagent.rag.KnowledgeStore;
import com.zhituagent.session.SessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = ZhituAgentApplication.class,
        properties = {
                "zhitu.infrastructure.redis-enabled=false"
        }
)
class InfrastructureWiringTest {

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private MemoryStore memoryStore;

    @Autowired
    private KnowledgeStore knowledgeStore;

    @Test
    void shouldUseInMemoryInfrastructureBeansByDefault() {
        assertThat(sessionRepository.getClass().getSimpleName()).isEqualTo("InMemorySessionRepository");
        assertThat(memoryStore.getClass().getSimpleName()).isEqualTo("InMemoryMemoryStore");
        assertThat(knowledgeStore.getClass().getSimpleName()).isEqualTo("InMemoryKnowledgeStore");
    }
}
