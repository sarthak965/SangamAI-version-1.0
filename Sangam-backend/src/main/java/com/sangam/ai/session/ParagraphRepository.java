package com.sangam.ai.session;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface ParagraphRepository extends JpaRepository<Paragraph, UUID> {
    List<Paragraph> findByNodeIdOrderByIndex(UUID nodeId);
    java.util.Optional<Paragraph> findByNodeIdAndIndex(UUID nodeId, int index);
}
