package com.sangam.ai.session;

import com.sangam.ai.environment.EnvironmentMember;
import com.sangam.ai.environment.EnvironmentMemberRepository;
import com.sangam.ai.environment.EnvironmentRepository;
import com.sangam.ai.realtime.CentrifugoService;
import com.sangam.ai.session.dto.*;
import com.sangam.ai.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    private static final int MAX_DEPTH = 3;

    private final SessionRepository sessionRepository;
    private final ConversationNodeRepository nodeRepository;
    private final ParagraphRepository paragraphRepository;
    private final SnapshotCacheService snapshotCacheService;
    private final EnvironmentRepository environmentRepository;
    private final EnvironmentMemberRepository memberRepository;
    private final AiJobQueue jobQueue;
    private final CentrifugoService centrifugoService;
    private final ContentBlockParser contentBlockParser;

    public record BlockAskResult(UUID nodeId, UUID paragraphId, int blockIndex) {
    }

    @Transactional
    public Session createSession(UUID environmentId, String title, User user) {
        var environment = environmentRepository.findById(environmentId)
                .orElseThrow(() -> new IllegalArgumentException("Environment not found"));

        assertCanAskRootAi(environmentId, user);

        return sessionRepository.save(Session.builder()
                .environment(environment)
                .createdBy(user)
                .title(title)
                .status(Session.Status.OPEN)
                .build());
    }

    @Transactional
    public UUID ask(UUID sessionId, String question, User user) {
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found"));

        assertCanAskRootAi(session.getEnvironment().getId(), user);

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

        runAfterCommit(() -> {
            centrifugoService.publishNodeCreated(
                    sessionId,
                    new HashMap<>() {{
                        put("type", "root_node_created");
                        put("nodeId", node.getId().toString());
                        put("depth", 0);
                        put("question", question);
                        put("askedBy", user.getUsername());
                    }}
            );

            jobQueue.enqueue(AiJob.rootJob(
                    node.getId(), sessionId, question, user.getId()));
            snapshotCacheService.invalidate(sessionId);
        });

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

        return createChildNode(parentNode, targetParagraph, question, user);
    }

    @Transactional
    public BlockAskResult askOnBlock(UUID parentNodeId, int blockIndex,
                                     String question, User user) {
        ConversationNode parentNode = nodeRepository.findById(parentNodeId)
                .orElseThrow(() -> new IllegalArgumentException("Node not found"));

        if (parentNode.getDepth() >= MAX_DEPTH) {
            throw new IllegalArgumentException(
                    "Maximum conversation depth (" + MAX_DEPTH + ") reached");
        }

        assertCanInteractWithAi(
                parentNode.getSession().getEnvironment().getId(), user);

        if (parentNode.getStatus() == ConversationNode.Status.STREAMING) {
            throw new IllegalArgumentException("Wait for the answer to finish before starting a thread");
        }

        List<String> blocks = contentBlockParser.split(parentNode.getFullContent());
        if (blockIndex < 0 || blockIndex >= blocks.size()) {
            throw new IllegalArgumentException("Selected block not found");
        }

        Paragraph anchor = findOrCreateAnchor(parentNode, blockIndex, blocks.get(blockIndex));

        UUID childNodeId = createChildNode(parentNode, anchor, question, user);
        return new BlockAskResult(childNodeId, anchor.getId(), blockIndex);
    }

    public SessionSnapshotDto getSnapshot(UUID sessionId, User user) {
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found"));

        if (!memberRepository.existsByEnvironmentIdAndUserId(
                session.getEnvironment().getId(), user.getId())) {
            throw new SecurityException("You are not a member of this environment");
        }

        // Load all nodes once — used for both the streaming check and building the snapshot
        List<ConversationNode> allNodes =
                nodeRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);

        boolean hasStreamingNodes = allNodes.stream()
                .anyMatch(n -> n.getStatus() == ConversationNode.Status.STREAMING);

        // Only use cache when nothing is streaming — during streaming we need
        // fresh DB data every poll so the frontend sees fullContent grow.
        if (!hasStreamingNodes) {
            SessionSnapshotDto cached = snapshotCacheService.get(sessionId);
            if (cached != null) {
                return cached;
            }
        }

        List<ConversationNodeDto> rootNodes = allNodes.stream()
                .filter(n -> n.getDepth() == 0)
                .map(n -> buildNodeDto(n, allNodes))
                .toList();

        SessionSnapshotDto snapshot = new SessionSnapshotDto(
                session.getId(),
                session.getTitle(),
                session.getStatus().name(),
                rootNodes
        );

        // Only cache when all nodes are complete — never cache mid-stream
        if (!hasStreamingNodes) {
            snapshotCacheService.put(sessionId, snapshot);
        }

        return snapshot;
    }

    private ConversationNodeDto buildNodeDto(
            ConversationNode node, List<ConversationNode> allNodes) {

        List<Paragraph> anchors = paragraphRepository.findByNodeIdOrderByIndex(node.getId());
        List<String> blocks = contentBlockParser.split(node.getFullContent());

        List<ConversationNode> children = allNodes.stream()
                .filter(n -> node.getId().equals(
                        n.getParent() != null ? n.getParent().getId() : null))
                .toList();

        Map<Integer, Paragraph> anchorsByIndex = anchors.stream()
                .collect(java.util.stream.Collectors.toMap(Paragraph::getIndex, p -> p));

        List<ParagraphDto> paragraphDtos = java.util.stream.IntStream.range(0, blocks.size())
                .mapToObj(index -> {
                    Paragraph anchor = anchorsByIndex.get(index);
                    int childCount = anchor == null
                            ? 0
                            : (int) children.stream()
                            .filter(c -> anchor.getId().equals(c.getParagraphId()))
                            .count();
                    return ParagraphDto.display(
                            anchor != null ? anchor.getId() : null,
                            index,
                            blocks.get(index),
                            childCount
                    );
                })
                .toList();

        List<ConversationNodeDto> childDtos = children.stream()
                .map(c -> buildNodeDto(c, allNodes))
                .toList();

        return ConversationNodeDto.from(node, paragraphDtos, childDtos);
    }

    private UUID createChildNode(
            ConversationNode parentNode,
            Paragraph anchor,
            String question,
            User user) {

        if (parentNode.getDepth() >= MAX_DEPTH) {
            throw new IllegalArgumentException(
                    "Maximum conversation depth (" + MAX_DEPTH + ") reached");
        }

        assertCanInteractWithAi(
                parentNode.getSession().getEnvironment().getId(), user);

        if (parentNode.getStatus() == ConversationNode.Status.STREAMING) {
            throw new IllegalArgumentException("Wait for the answer to finish before starting a thread");
        }

        ConversationNode childNode = nodeRepository.save(ConversationNode.builder()
                .session(parentNode.getSession())
                .parent(parentNode)
                .paragraphId(anchor.getId())
                .depth(parentNode.getDepth() + 1)
                .question(question)
                .askedBy(user)
                .fullContent("")
                .status(ConversationNode.Status.STREAMING)
                .build());

        runAfterCommit(() -> {
            centrifugoService.publishNodeCreated(
                    parentNode.getSession().getId(),
                    new HashMap<>() {{
                        put("type", "child_node_created");
                        put("nodeId", childNode.getId().toString());
                        put("parentNodeId", parentNode.getId().toString());
                        put("paragraphId", anchor.getId().toString());
                        put("blockIndex", anchor.getIndex());
                        put("depth", childNode.getDepth());
                        put("question", question);
                        put("askedBy", user.getUsername());
                    }}
            );

            jobQueue.enqueue(AiJob.paragraphJob(
                    childNode.getId(), parentNode.getSession().getId(),
                    question, user.getId(), parentNode.getId(), anchor.getId()));
            snapshotCacheService.invalidate(parentNode.getSession().getId());
        });

        return childNode.getId();
    }

    private Paragraph findOrCreateAnchor(ConversationNode parentNode, int blockIndex, String blockContent) {
        return paragraphRepository.findByNodeIdAndIndex(parentNode.getId(), blockIndex)
                .orElseGet(() -> {
                    try {
                        return paragraphRepository.save(Paragraph.builder()
                                .node(parentNode)
                                .index(blockIndex)
                                .content(blockContent)
                                .build());
                    } catch (DataIntegrityViolationException e) {
                        return paragraphRepository.findByNodeIdAndIndex(parentNode.getId(), blockIndex)
                                .orElseThrow(() -> e);
                    }
                });
    }

    private void assertCanAskRootAi(UUID environmentId, User user) {
        EnvironmentMember member = memberRepository
                .findByEnvironmentIdAndUserId(environmentId, user.getId())
                .orElseThrow(() -> new SecurityException("You are not a member"));

        if (member.getEnvironment().getHost().getId().equals(user.getId())
                || member.getRole() == EnvironmentMember.Role.CO_HOST) {
            return;
        }

        throw new SecurityException(
                "Only the owner or a co-host can ask the root AI in this environment");
    }

    private void assertCanInteractWithAi(UUID environmentId, User user) {
        EnvironmentMember member = memberRepository
                .findByEnvironmentIdAndUserId(environmentId, user.getId())
                .orElseThrow(() -> new SecurityException("You are not a member"));

        boolean isOwner = member.getEnvironment().getHost().getId().equals(user.getId());
        boolean isCoHost = member.getRole() == EnvironmentMember.Role.CO_HOST;

        if (!isOwner && !isCoHost && !member.isCanInteractWithAi()) {
            throw new SecurityException(
                    "You don't have permission to interact with AI");
        }
    }

    private void runAfterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            action.run();
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        action.run();
                    }
                }
        );
    }

    public List<Map<String, Object>> getSessionsForEnvironment(
            UUID environmentId, User user) {

        if (!memberRepository.existsByEnvironmentIdAndUserId(
                environmentId, user.getId())) {
            throw new SecurityException("You are not a member of this environment");
        }

        return sessionRepository
                .findByEnvironmentIdOrderByCreatedAtDesc(environmentId)
                .stream()
                .map(s -> {
                    Map<String, Object> map = new java.util.HashMap<>();
                    map.put("sessionId", s.getId());
                    map.put("title", s.getTitle() != null ? s.getTitle() : "");
                    map.put("status", s.getStatus().name());
                    map.put("createdAt", s.getCreatedAt());
                    map.put("createdBy", s.getCreatedBy().getUsername());
                    return map;
                })
                .toList();
    }
}
