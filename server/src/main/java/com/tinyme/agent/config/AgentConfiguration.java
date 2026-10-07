package com.tinyme.agent.config;

import com.tinyme.agent.client.ManagedAgentApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.time.Clock;

@Configuration(proxyBeanMethods = false)
class AgentConfiguration {
    @Bean
    ManagedAgentApi managedAgentApi(@Value("${tinyme.agent.api-key}") String apiKey) {
        return new ManagedAgentApi(apiKey, URI.create("https://api.anthropic.com"));
    }

    @Bean
    Clock agentClock() {
        return Clock.systemUTC();
    }
}
