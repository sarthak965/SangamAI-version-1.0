package com.sangam.ai.environment;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EnvironmentMemberRepository extends JpaRepository<EnvironmentMember, UUID> {

    // All members of a given environment
    List<EnvironmentMember> findByEnvironmentId(UUID environmentId);

    // All environments a user belongs to
    List<EnvironmentMember> findByUserId(UUID userId);

    // Find one specific membership record
    Optional<EnvironmentMember> findByEnvironmentIdAndUserId(UUID environmentId, UUID userId);

    // Is this user already a member?
    boolean existsByEnvironmentIdAndUserId(UUID environmentId, UUID userId);
}