package com.sangam.ai.session;

import com.sangam.ai.common.response.ApiResponse;
import com.sangam.ai.environment.EnvironmentMemberRepository;
import com.sangam.ai.session.dto.AskOnParagraphRequest;
import com.sangam.ai.session.dto.SessionSnapshotDto;
import com.sangam.ai.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
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

    // POST /api/sessions/{sessionId}/ask  — root level question
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
        return ResponseEntity.ok(ApiResponse.ok(Map.of("nodeId", nodeId)));
    }

    // POST /api/nodes/{nodeId}/paragraphs/{paragraphId}/ask — paragraph follow-up
    @PostMapping("/nodes/{nodeId}/paragraphs/{paragraphId}/ask")
    public ResponseEntity<ApiResponse<Map<String, UUID>>> askOnParagraph(
            @PathVariable UUID nodeId,
            @PathVariable UUID paragraphId,
            @Valid @RequestBody AskOnParagraphRequest request,
            @AuthenticationPrincipal User currentUser) {

        UUID childNodeId = sessionService.askOnParagraph(
                nodeId, paragraphId, request.question(), currentUser);

        return ResponseEntity.ok(ApiResponse.ok(Map.of("nodeId", childNodeId)));
    }

    @PostMapping("/nodes/{nodeId}/blocks/{blockIndex}/ask")
    public ResponseEntity<ApiResponse<Map<String, Object>>> askOnBlock(
            @PathVariable UUID nodeId,
            @PathVariable int blockIndex,
            @Valid @RequestBody AskOnParagraphRequest request,
            @AuthenticationPrincipal User currentUser) {

        SessionService.BlockAskResult result = sessionService.askOnBlock(
                nodeId, blockIndex, request.question(), currentUser);

        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "nodeId", result.nodeId(),
                "paragraphId", result.paragraphId(),
                "blockIndex", result.blockIndex()
        )));
    }

    // GET /api/sessions/{sessionId}/snapshot — full tree for new members
    @GetMapping("/sessions/{sessionId}/snapshot")
    public ResponseEntity<ApiResponse<SessionSnapshotDto>> getSnapshot(
            @PathVariable UUID sessionId,
            @AuthenticationPrincipal User currentUser) {

        return ResponseEntity.ok(ApiResponse.ok(
                sessionService.getSnapshot(sessionId, currentUser)));
    }
    @GetMapping("/environments/{environmentId}/sessions")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getSessions(
            @PathVariable UUID environmentId,
            @AuthenticationPrincipal User currentUser) {

        return ResponseEntity.ok(ApiResponse.ok(
                sessionService.getSessionsForEnvironment(environmentId, currentUser)));
    }
}
