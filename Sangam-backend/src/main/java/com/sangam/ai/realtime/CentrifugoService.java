package com.sangam.ai.realtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CentrifugoService {

    private final WebClient centrifugoWebClient;

    /**
     * Publishes any object as JSON to a Centrifugo channel.
     * Centrifugo immediately fans this out to every client
     * subscribed to that channel.
     *
     * This is fire-and-forget — we subscribe() to trigger the
     * call but don't wait for the result. If it fails, we log
     * a warning but don't crash the streaming flow.
     */
    public void publish(String channel, Object data) {
        centrifugoWebClient.post()
                .bodyValue(Map.of(
                        "channel", channel,
                        "data", data
                ))
                .retrieve()
                .bodyToMono(String.class)
                .doOnError(e -> log.warn(
                        "Failed to publish to Centrifugo channel {}: {}",
                        channel, e.getMessage()))
                .subscribe();
    }

    // ---- Typed convenience methods ----
    // These give you a clean API so service classes
    // never have to think about channel naming.

    public void publishTokenChunk(UUID nodeId, String chunk) {
        publish("node:" + nodeId + ":stream",
                Map.of("type", "chunk", "content", chunk));
    }

    public void publishStreamComplete(UUID nodeId) {
        publish("node:" + nodeId + ":stream",
                Map.of("type", "done"));
    }

    public void publishParagraphReady(UUID nodeId, UUID paragraphId,
                                      int index, String content) {
        publish("node:" + nodeId + ":stream", Map.of(
                "type", "paragraph_ready",
                "paragraphId", paragraphId.toString(),
                "index", index,
                "content", content
        ));
    }

    public void publishNodeCreated(UUID sessionId, Object nodeData) {
        publish("session:" + sessionId + ":events", nodeData);
    }
}