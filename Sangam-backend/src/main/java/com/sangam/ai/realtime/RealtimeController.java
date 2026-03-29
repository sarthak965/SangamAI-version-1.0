package com.sangam.ai.realtime;

import com.sangam.ai.common.response.ApiResponse;
import com.sangam.ai.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/centrifugo")
@RequiredArgsConstructor
public class RealtimeController {

    private final CentrifugoTokenService centrifugoTokenService;

    /**
     * GET /api/centrifugo/token
     *
     * The frontend calls this once after login.
     * It uses the returned token to connect to Centrifugo WebSocket.
     *
     * The frontend code will look something like:
     *   const { token } = await fetch('/api/centrifugo/token')
     *   const centrifuge = new Centrifuge('ws://localhost:8001/connection/websocket', { token })
     *   centrifuge.connect()
     */
    @GetMapping("/token")
    public ResponseEntity<ApiResponse<Map<String, String>>> getConnectionToken(
            @AuthenticationPrincipal User currentUser) {

        String token = centrifugoTokenService
                .generateConnectionToken(currentUser.getId().toString());

        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "token", token,
                "wsUrl", "ws://localhost:8001/connection/websocket"
        )));
    }

    /**
     * GET /api/centrifugo/token/subscription?channel=node:abc:stream
     *
     * The frontend calls this when it wants to subscribe to a specific channel.
     * Returns a subscription token for that channel.
     *
     * The frontend code will look something like:
     *   centrifuge.newSubscription('node:abc:stream', {
     *     getToken: () => fetch('/api/centrifugo/token/subscription?channel=node:abc:stream')
     *   })
     */
    @GetMapping("/token/subscription")
    public ResponseEntity<ApiResponse<Map<String, String>>> getSubscriptionToken(
            @RequestParam String channel,
            @AuthenticationPrincipal User currentUser) {

        String token = centrifugoTokenService.generateSubscriptionToken(
                currentUser.getId().toString(), channel);

        return ResponseEntity.ok(ApiResponse.ok(Map.of("token", token)));
    }
}