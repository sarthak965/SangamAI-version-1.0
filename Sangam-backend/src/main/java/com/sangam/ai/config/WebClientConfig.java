package com.sangam.ai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    // A WebClient pre-configured for Centrifugo.
    // We inject the base URL from properties so it's configurable.
    @Bean
    public WebClient centrifugoWebClient(
            @org.springframework.beans.factory.annotation.Value(
                    "${centrifugo.api-url}") String centrifugoApiUrl,
            @org.springframework.beans.factory.annotation.Value(
                    "${centrifugo.api-key}") String centrifugoApiKey) {

        return WebClient.builder()
                .baseUrl(centrifugoApiUrl)
                .defaultHeader("X-API-Key", centrifugoApiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }
}