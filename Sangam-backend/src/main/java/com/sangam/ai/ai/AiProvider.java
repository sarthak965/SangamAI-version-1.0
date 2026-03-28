package com.sangam.ai.ai;

import reactor.core.publisher.Flux;

/**
 * This interface is the ONLY thing your application ever sees.
 * It knows nothing about Anthropic, OpenAI, or any other provider.
 *
 * It takes a list of messages (the assembled context) and returns
 * a Flux<String> — a reactive stream of text chunks.
 * Each chunk is a small piece of the AI's response as it's generated.
 *
 * Flux is from Project Reactor (included with Spring WebFlux).
 * Think of Flux<String> as a stream that will emit many String
 * values over time, one by one, then complete.
 */
public interface AiProvider {
    Flux<String> streamResponse(java.util.List<AiMessage> messages);
}