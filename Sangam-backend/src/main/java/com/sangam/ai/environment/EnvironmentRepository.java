package com.sangam.ai.environment;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface EnvironmentRepository extends JpaRepository<Environment, UUID> {
    Optional<Environment> findByInviteCode(String inviteCode);
    boolean existsByInviteCode(String inviteCode);
}