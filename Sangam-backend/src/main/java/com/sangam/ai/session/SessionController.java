package com.sangam.ai.session;

import com.sangam.ai.common.response.ApiResponse;
import com.sangam.ai.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SessionController {

    private final SessionService sessionService;

    // POST /api/environments/{environmentId}/sessions
    @PostMapping("/environments/{environmentId}/sessions")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createSession(
            @PathVariable UUID environmentId,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal User currentUser) {

        Session session = sessionService.createSession(
                environmentId, body.get("title"), currentUser);

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(Map.of(
                "sessionId", session.getId(),
                "title", session.getTitle() != null ? session.getTitle() : "",
                "status", session.getStatus().name()
        )));
    }

    // POST /api/sessions/{sessionId}/ask
    @PostMapping("/sessions/{sessionId}/ask")
    public ResponseEntity<ApiResponse<Map<String, UUID>>> ask(
            @PathVariable UUID sessionId,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal User currentUser) {

        String question = body.get("question");
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("Question cannot be blank");
        }

        UUID nodeId = sessionService.ask(sessionId, question, currentUser);

        // We return the nodeId immediately — the client uses this to
        // subscribe to node:{nodeId}:stream on Centrifugo and receive tokens
        return ResponseEntity.ok(ApiResponse.ok(Map.of("nodeId", nodeId)));
    }
}