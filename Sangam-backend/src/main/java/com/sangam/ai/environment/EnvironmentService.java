package com.sangam.ai.environment;

import com.sangam.ai.environment.dto.*;
import com.sangam.ai.user.User;
import com.sangam.ai.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EnvironmentService {

    private final EnvironmentRepository environmentRepository;
    private final EnvironmentMemberRepository memberRepository;
    private final UserRepository userRepository;

    /**
     * Creates a new environment and automatically adds the creator as HOST.
     * @Transactional means: if anything fails inside this method,
     * ALL database changes are rolled back. So you never end up with
     * an environment that has no host member record, or vice versa.
     */
    @Transactional
    public EnvironmentResponse createEnvironment(CreateEnvironmentRequest request, User host) {

        Environment environment = Environment.builder()
                .name(request.name())
                .description(request.description())
                .host(host)
                .inviteCode(generateUniqueInviteCode())
                .build();

        Environment saved = environmentRepository.save(environment);

        // Automatically add the creator as a HOST member with AI permission
        EnvironmentMember hostMember = EnvironmentMember.builder()
                .environment(saved)
                .user(host)
                .role(EnvironmentMember.Role.HOST)
                .canInteractWithAi(true)
                .build();

        memberRepository.save(hostMember);

        return EnvironmentResponse.from(saved);
    }

    /**
     * Lets a user join an environment using its invite code.
     */
    @Transactional
    public EnvironmentResponse joinByInviteCode(String inviteCode, User user) {

        Environment environment = environmentRepository.findByInviteCode(inviteCode)
                .orElseThrow(() -> new IllegalArgumentException("Invalid invite code"));

        if (memberRepository.existsByEnvironmentIdAndUserId(
                environment.getId(), user.getId())) {
            throw new IllegalArgumentException("You are already a member of this environment");
        }

        EnvironmentMember member = EnvironmentMember.builder()
                .environment(environment)
                .user(user)
                .role(EnvironmentMember.Role.MEMBER)
                .canInteractWithAi(false)  // host must explicitly grant this
                .build();

        memberRepository.save(member);
        return EnvironmentResponse.from(environment);
    }

    /**
     * Returns all members of an environment.
     * Only members of the environment can call this.
     */
    public List<MemberResponse> getMembers(UUID environmentId, User requestingUser) {
        assertIsMember(environmentId, requestingUser);

        return memberRepository.findByEnvironmentId(environmentId)
                .stream()
                .map(MemberResponse::from)
                .toList();
    }

    /**
     * Host updates whether a member can interact with the AI.
     * Only the HOST can call this.
     */
    @Transactional
    public MemberResponse updateMemberPermission(UUID environmentId,
                                                 UpdatePermissionRequest request,
                                                 User requestingUser) {
        assertIsHost(environmentId, requestingUser);

        User targetUser = userRepository.findByUsername(request.username())
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        EnvironmentMember member = memberRepository
                .findByEnvironmentIdAndUserId(environmentId, targetUser.getId())
                .orElseThrow(() -> new IllegalArgumentException("User is not a member"));

        member.setCanInteractWithAi(request.canInteractWithAi());
        return MemberResponse.from(memberRepository.save(member));
    }

    /**
     * Returns all environments the current user belongs to.
     */
    public List<EnvironmentResponse> getMyEnvironments(User user) {
        return memberRepository.findByUserId(user.getId())
                .stream()
                .map(m -> EnvironmentResponse.from(m.getEnvironment()))
                .toList();
    }

    // ---- private helpers ----

    private void assertIsMember(UUID environmentId, User user) {
        if (!memberRepository.existsByEnvironmentIdAndUserId(environmentId, user.getId())) {
            throw new SecurityException("You are not a member of this environment");
        }
    }

    private void assertIsHost(UUID environmentId, User user) {
        EnvironmentMember member = memberRepository
                .findByEnvironmentIdAndUserId(environmentId, user.getId())
                .orElseThrow(() -> new SecurityException("You are not a member"));

        if (member.getRole() != EnvironmentMember.Role.HOST) {
            throw new SecurityException("Only the host can do this");
        }
    }

    private String generateUniqueInviteCode() {
        String code;
        // Keep generating until we find one that doesn't exist.
        // Collision probability is extremely low with 8 hex chars (4 billion combinations).
        do {
            code = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        } while (environmentRepository.existsByInviteCode(code));
        return code;
    }
}