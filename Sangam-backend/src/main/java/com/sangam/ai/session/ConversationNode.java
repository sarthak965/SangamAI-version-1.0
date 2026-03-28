package com.sangam.ai.session;

import com.sangam.ai.user.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "conversation_nodes")
@Getter @Setter @Builder
@NoArgsConstructor @AllArgsConstructor
public class ConversationNode {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private Session session;

    // Self-referential relationship — a node's parent is also a node.
    // nullable = true because the root node has no parent.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private ConversationNode parent;

    // We store paragraph_id as a raw UUID here.
    // In Stage 3 we'll add the full Paragraph entity relationship.
    @Column(name = "paragraph_id")
    private UUID paragraphId;

    @Column(nullable = false)
    private int depth = 0;

    @Column(columnDefinition = "TEXT")
    private String question;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asked_by")
    private User askedBy;

    // Starts empty, built up token by token during streaming
    @Column(columnDefinition = "TEXT", nullable = false)
    private String fullContent = "";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.STREAMING;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public enum Status { STREAMING, COMPLETE }
}