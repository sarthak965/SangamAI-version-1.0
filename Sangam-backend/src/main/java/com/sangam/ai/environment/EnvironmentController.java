package com.sangam.ai.environment;

import com.sangam.ai.common.response.ApiResponse;
import com.sangam.ai.environment.dto.*;
import com.sangam.ai.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/environments")
@RequiredArgsConstructor
public class EnvironmentController {

    private final EnvironmentService environmentService;

    /**
     * @AuthenticationPrincipal is the key annotation here.
     * Remember how JwtAuthFilter set the User object on the
     * SecurityContext? This annotation pulls that User back out
     * automatically. So you always know exactly who is making
     * the request without any extra lookup.
     */

    @PostMapping
    public ResponseEntity<ApiResponse<EnvironmentResponse>> create(
            @Valid @RequestBody CreateEnvironmentRequest request,
            @AuthenticationPrincipal User currentUser) {

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(
                        environmentService.createEnvironment(request, currentUser)));
    }

    @PostMapping("/join/{inviteCode}")
    public ResponseEntity<ApiResponse<EnvironmentResponse>> join(
            @PathVariable String inviteCode,
            @AuthenticationPrincipal User currentUser) {

        return ResponseEntity.ok(ApiResponse.ok(
                environmentService.joinByInviteCode(inviteCode, currentUser)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<EnvironmentResponse>>> myEnvironments(
            @AuthenticationPrincipal User currentUser) {

        return ResponseEntity.ok(ApiResponse.ok(
                environmentService.getMyEnvironments(currentUser)));
    }

    @GetMapping("/{environmentId}/members")
    public ResponseEntity<ApiResponse<List<MemberResponse>>> getMembers(
            @PathVariable UUID environmentId,
            @AuthenticationPrincipal User currentUser) {

        return ResponseEntity.ok(ApiResponse.ok(
                environmentService.getMembers(environmentId, currentUser)));
    }

    @PatchMapping("/{environmentId}/members/permissions")
    public ResponseEntity<ApiResponse<MemberResponse>> updatePermission(
            @PathVariable UUID environmentId,
            @RequestBody UpdatePermissionRequest request,
            @AuthenticationPrincipal User currentUser) {

        return ResponseEntity.ok(ApiResponse.ok(
                environmentService.updateMemberPermission(
                        environmentId, request, currentUser)));
    }
}