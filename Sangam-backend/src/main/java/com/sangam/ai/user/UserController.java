package com.sangam.ai.user;

import com.sangam.ai.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    /**
     * GET /api/users/me
     *
     * Returns the currently logged-in user's profile.
     * The frontend calls this on app load to know who is logged in.
     */
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getCurrentUser(
            @AuthenticationPrincipal User currentUser) {

        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "id", currentUser.getId(),
                "username", currentUser.getUsername(),
                "displayName", currentUser.getDisplayName(),
                "email", currentUser.getEmail()
        )));
    }
}