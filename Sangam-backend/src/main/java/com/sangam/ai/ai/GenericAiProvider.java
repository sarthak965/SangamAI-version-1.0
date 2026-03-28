package com.sangam.ai.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * ONE provider that works with ANY OpenAI-compatible API.
 *
 * Groq, OpenAI, Together AI, Mistral, Fireworks, Gemini (OpenAI compat),
 * Anthropic (OpenAI compat endpoint) — all use the same request/response
 * format. You just point this at a different base URL with a different key.
 *
 * To switch providers you change ONLY application.properties.
 * This class never changes.
 */
@Slf4j
@Component
public class GenericAiProvider implements AiProvider {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String model;

    public GenericAiProvider(
            @Value("${app.ai.base-url}") String baseUrl,
            @Value("${app.ai.api-key}") String apiKey,
            @Value("${app.ai.model}") String model,
            ObjectMapper objectMapper) {

        this.model = model;
        this.objectMapper = objectMapper;

        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Override
    public Flux<String> streamResponse(List<AiMessage> messages) {

        List<Map<String, String>> formattedMessages = messages.stream()
                .map(m -> Map.of("role", m.role(), "content", m.content()))
                .toList();

        Map<String, Object> requestBody = Map.of(
                "model", model,
                "stream", true,
                "messages", formattedMessages
        );

        return webClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToFlux(String.class)
                .filter(line -> line.startsWith("data: "))
                .map(line -> line.substring(6).trim())
                .filter(data -> !data.equals("[DONE]"))
                .flatMap(data -> {
                    try {
                        JsonNode node = objectMapper.readTree(data);
                        String text = node
                                .path("choices")
                                .path(0)
                                .path("delta")
                                .path("content")
                                .asText("");
                        return text.isEmpty() ? Flux.empty() : Flux.just(text);
                    } catch (Exception e) {
                        log.warn("Failed to parse SSE event: {}", data);
                        return Flux.empty();
                    }
                })
                .doOnError(e -> log.error("AI streaming error: {}", e.getMessage()));
    }
}