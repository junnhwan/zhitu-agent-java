package com.zhituagent.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhituagent.memory.InMemoryMemoryStore;
import com.zhituagent.memory.MemoryStore;
import com.zhituagent.memory.MemoryLock;
import com.zhituagent.memory.NoopMemoryLock;
import com.zhituagent.memory.RedisMemoryStore;
import com.zhituagent.memory.RedisMemoryLock;
import com.zhituagent.rag.ElasticsearchKnowledgeStore;
import com.zhituagent.rag.InMemoryKnowledgeStore;
import com.zhituagent.rag.KnowledgeStore;
import com.zhituagent.rag.OpenAiCompatibleRerankClient;
import com.zhituagent.rag.RerankClient;
import com.zhituagent.session.InMemorySessionRepository;
import com.zhituagent.session.RedisSessionRepository;
import com.zhituagent.session.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.http.HttpClient;

@Configuration
public class InfrastructureConfig {

    private static final Logger log = LoggerFactory.getLogger(InfrastructureConfig.class);

    @Bean
    @ConditionalOnProperty(prefix = "zhitu.infrastructure", name = "redis-enabled", havingValue = "true")
    SessionRepository redisSessionRepository(StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper) {
        return new RedisSessionRepository(stringRedisTemplate, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(SessionRepository.class)
    SessionRepository inMemorySessionRepository() {
        return new InMemorySessionRepository();
    }

    @Bean
    @ConditionalOnProperty(prefix = "zhitu.infrastructure", name = "redis-enabled", havingValue = "true")
    MemoryStore redisMemoryStore(StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper) {
        return new RedisMemoryStore(stringRedisTemplate, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(MemoryStore.class)
    MemoryStore inMemoryMemoryStore() {
        return new InMemoryMemoryStore();
    }

    @Bean
    @ConditionalOnProperty(prefix = "zhitu.infrastructure", name = "redis-enabled", havingValue = "true")
    MemoryLock redisMemoryLock(StringRedisTemplate stringRedisTemplate) {
        return new RedisMemoryLock(stringRedisTemplate);
    }

    @Bean
    @ConditionalOnMissingBean(MemoryLock.class)
    MemoryLock noopMemoryLock() {
        return new NoopMemoryLock();
    }

    @Bean
    @ConditionalOnProperty(prefix = "zhitu.infrastructure", name = "elasticsearch-enabled", havingValue = "true")
    KnowledgeStore elasticsearchKnowledgeStore(ElasticsearchClient esClient,
                                               EsProperties esProperties,
                                               EmbeddingProperties embeddingProperties) {
        return new ElasticsearchKnowledgeStore(esClient, esProperties, embeddingProperties);
    }

    @Bean
    @ConditionalOnMissingBean(KnowledgeStore.class)
    KnowledgeStore inMemoryKnowledgeStore() {
        return new InMemoryKnowledgeStore();
    }

    @Bean
    RerankClient rerankClient(RerankProperties rerankProperties, ObjectMapper objectMapper) {
        return new OpenAiCompatibleRerankClient(HttpClient.newHttpClient(), objectMapper, rerankProperties);
    }

    /**
     * Print active store implementations on startup so eval/baseline runs can grep
     * "ZhituAgent active stores" out of the log to prove which KnowledgeStore was wired
     * (silent fallback to InMemoryKnowledgeStore is otherwise invisible — see audit).
     *
     * Uses ApplicationStartedEvent (fires before ApplicationRunner.run) instead of
     * ApplicationReadyEvent, because EvalApplicationRunner calls SpringApplication.exit
     * when exit-after-run=true, which suppresses ReadyEvent.
     */
    @EventListener(ApplicationStartedEvent.class)
    public void logActiveStores(ApplicationStartedEvent event) {
        ApplicationContext ctx = event.getApplicationContext();
        KnowledgeStore ks = ctx.getBean(KnowledgeStore.class);
        SessionRepository sr = ctx.getBean(SessionRepository.class);
        MemoryStore ms = ctx.getBean(MemoryStore.class);
        log.info(
                "ZhituAgent active stores: KnowledgeStore={} (nativeHybrid={}), SessionRepository={}, MemoryStore={}",
                ks.getClass().getSimpleName(),
                ks instanceof ElasticsearchKnowledgeStore,
                sr.getClass().getSimpleName(),
                ms.getClass().getSimpleName());
    }
}
