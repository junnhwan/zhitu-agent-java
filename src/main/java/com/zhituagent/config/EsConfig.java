package com.zhituagent.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Elasticsearch client wiring for v3 retrieval stack.
 * Gated by {@code zhitu.infrastructure.elasticsearch-enabled=true} so unit tests
 * keep using {@code InMemoryKnowledgeStore} without requiring an ES instance.
 *
 * <p>Local dev points at the cloud ES at {@code http://106.12.190.62:9200} with no auth
 * (firewall whitelist is the security boundary). Production should set
 * {@code zhitu.elasticsearch.username/password} for basic auth.
 */
@Configuration
@EnableConfigurationProperties(EsProperties.class)
@ConditionalOnProperty(prefix = "zhitu.infrastructure", name = "elasticsearch-enabled", havingValue = "true")
public class EsConfig {

    @Bean(destroyMethod = "close")
    public RestClient elasticsearchRestClient(EsProperties props) {
        HttpHost host = new HttpHost(props.getHost(), props.getPort(), props.getScheme());
        var builder = RestClient.builder(host);

        if (props.hasAuth()) {
            BasicCredentialsProvider provider = new BasicCredentialsProvider();
            provider.setCredentials(
                    AuthScope.ANY,
                    new UsernamePasswordCredentials(props.getUsername(), props.getPassword()));
            builder.setHttpClientConfigCallback(http -> http.setDefaultCredentialsProvider(provider));
        }

        return builder.build();
    }

    @Bean
    public ElasticsearchTransport elasticsearchTransport(RestClient restClient) {
        return new RestClientTransport(restClient, new JacksonJsonpMapper());
    }

    @Bean
    public ElasticsearchClient elasticsearchClient(ElasticsearchTransport transport) {
        return new ElasticsearchClient(transport);
    }
}
