package com.sangam.ai.session;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "paragraphs")
@Getter @Setter @Builder
@NoArgsConstructor @AllArgsConstructor
public class Paragraph {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "node_id", nullable = false)
    private ConversationNode node;

    @Column(nullable = false)
    private int index;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist protected void onCreate() {
        createdAt = Instant.now();
    }
}