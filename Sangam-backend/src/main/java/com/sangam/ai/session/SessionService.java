package com.sangam.ai.session;

import com.sangam.ai.environment.EnvironmentMember;
import com.sangam.ai.environment.EnvironmentMemberRepository;
import com.sangam.ai.environment.EnvironmentRepository;
import com.sangam.ai.realtime.CentrifugoService;
import com.sangam.ai.session.dto.*;
import com.sangam.ai.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    private static final int MAX_DEPTH = 3;

    private final SessionRepository sessionRepository;
    private final ConversationNodeRepository nodeRepository;
    private final ParagraphRepository paragraphRepository;
    private final EnvironmentRepository environmentRepository;
    private final EnvironmentMemberRepository memberRepository;
    private final AiJobQueue jobQueue;
    private final CentrifugoService centrifugoService;

    @Transactional
    public Session createSession(UUID environmentId, String title, User user) {
        var environment = environmentRepository.findById(environmentId)
                .orElseThrow(() -> new IllegalArgumentException("Environment not found"));

        assertCanInteractWithAi(environmentId, user);

        return sessionRepository.save(Session.builder()
                .environment(environment)
                .createdBy(user)
                .title(title)
                .status(Session.Status.OPEN)
                .build());
    }

    public UUID ask(UUID sessionId, String question, User user) {
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found"));

        assertCanInteractWithAi(session.getEnvironment().getId(), user);

        // Create node immediately
        ConversationNode node = nodeRepository.save(ConversationNode.builder()
                .session(session)
                .parent(null)
                .depth(0)
                .question(question)
                .askedBy(user)
                .fullContent("")
                .status(ConversationNode.Status.STREAMING)
                .build());

        // Push to Redis queue — worker picks it up and streams
        jobQueue.enqueue(AiJob.rootJob(
                node.getId(), sessionId, question, user.getId()));

        return node.getId();
    }

    public UUID askOnParagraph(UUID parentNodeId, UUID paragraphId,
                               String question, User user) {

        ConversationNode parentNode = nodeRepository.findById(parentNodeId)
                .orElseThrow(() -> new IllegalArgumentException("Node not found"));

        Paragraph targetParagraph = paragraphRepository.findById(paragraphId)
                .orElseThrow(() -> new IllegalArgumentException("Paragraph not found"));

        if (!targetParagraph.getNode().getId().equals(parentNodeId)) {
            throw new IllegalArgumentException(
                    "Paragraph does not belong to this node");
        }

        if (parentNode.getDepth() >= MAX_DEPTH) {
            throw new IllegalArgumentException(
                    "Maximum conversation depth (" + MAX_DEPTH + ") reached");
        }

        assertCanInteractWithAi(
                parentNode.getSession().getEnvironment().getId(), user);

        // Create child node immediately
        ConversationNode childNode = nodeRepository.save(ConversationNode.builder()
                .session(parentNode.getSession())
                .parent(parentNode)
                .paragraphId(paragraphId)
                .depth(parentNode.getDepth() + 1)
                .question(question)
                .askedBy(user)
                .fullContent("")
                .status(ConversationNode.Status.STREAMING)
                .build());

        // Notify all members a new thread has appeared
        centrifugoService.publishNodeCreated(
                parentNode.getSession().getId(),
                new HashMap<>() {{
                    put("type", "child_node_created");
                    put("nodeId", childNode.getId().toString());
                    put("parentNodeId", parentNodeId.toString());
                    put("paragraphId", paragraphId.toString());
                    put("depth", childNode.getDepth());
                    put("question", question);
                    put("askedBy", user.getUsername());
                }}
        );

        // Push to Redis queue
        jobQueue.enqueue(AiJob.paragraphJob(
                childNode.getId(), parentNode.getSession().getId(),
                question, user.getId(), parentNodeId, paragraphId));

        return childNode.getId();
    }

    public SessionSnapshotDto getSnapshot(UUID sessionId, User user) {
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found"));

        if (!memberRepository.existsByEnvironmentIdAndUserId(
                session.getEnvironment().getId(), user.getId())) {
            throw new SecurityException("You are not a member of this environment");
        }

        List<ConversationNode> allNodes =
                nodeRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);

        List<ConversationNodeDto> rootNodes = allNodes.stream()
                .filter(n -> n.getDepth() == 0)
                .map(n -> buildNodeDto(n, allNodes))
                .toList();

        return new SessionSnapshotDto(
                session.getId(),
                session.getTitle(),
                session.getStatus().name(),
                rootNodes
        );
    }

    private ConversationNodeDto buildNodeDto(
            ConversationNode node, List<ConversationNode> allNodes) {

        List<Paragraph> paragraphs =
                paragraphRepository.findByNodeIdOrderByIndex(node.getId());

        List<ConversationNode> children = allNodes.stream()
                .filter(n -> node.getId().equals(
                        n.getParent() != null ? n.getParent().getId() : null))
                .toList();

        List<ParagraphDto> paragraphDtos = paragraphs.stream()
                .map(p -> {
                    int childCount = (int) children.stream()
                            .filter(c -> p.getId().equals(c.getParagraphId()))
                            .count();
                    return ParagraphDto.from(p, childCount);
                })
                .toList();

        List<ConversationNodeDto> childDtos = children.stream()
                .map(c -> buildNodeDto(c, allNodes))
                .toList();

        return ConversationNodeDto.from(node, paragraphDtos, childDtos);
    }

    private void assertCanInteractWithAi(UUID environmentId, User user) {
        EnvironmentMember member = memberRepository
                .findByEnvironmentIdAndUserId(environmentId, user.getId())
                .orElseThrow(() -> new SecurityException("You are not a member"));

        if (!member.isCanInteractWithAi()) {
            throw new SecurityException(
                    "You don't have permission to interact with AI");
        }
    }
}