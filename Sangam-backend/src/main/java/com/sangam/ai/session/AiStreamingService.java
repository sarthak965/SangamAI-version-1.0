package com.sangam.ai.session;

import com.sangam.ai.ai.AiMessage;
import com.sangam.ai.ai.AiProvider;
import com.sangam.ai.ai.PromptPolicyService;
import com.sangam.ai.realtime.CentrifugoService;
import com.sangam.ai.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiStreamingService {

    private final ConversationNodeRepository nodeRepository;
    private final ParagraphRepository paragraphRepository;
    private final SessionRepository sessionRepository;
    private final UserRepository userRepository;
    private final AiProvider aiProvider;
    private final PromptPolicyService promptPolicyService;
    private final CentrifugoService centrifugoService;
    private final SnapshotCacheService snapshotCacheService;

    /**
     * Entry point called by AiJobWorker.
     * Routes to the right context builder based on job type,
     * then starts streaming.
     */
    public void process(AiJob job) {
        ConversationNode node = nodeRepository.findById(job.nodeId())
                .orElseThrow(() -> new IllegalStateException(
                        "Node not found: " + job.nodeId()));

        List<AiMessage> messages;

        if (job.type() == AiJob.JobType.ROOT) {
            Session session = sessionRepository.findById(job.sessionId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Session not found: " + job.sessionId()));
            messages = buildRootContext(session, job.question());

        } else {
            ConversationNode parentNode = nodeRepository
                    .findById(job.parentNodeId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Parent node not found: " + job.parentNodeId()));

            Paragraph targetParagraph = paragraphRepository
                    .findById(job.paragraphId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Paragraph not found: " + job.paragraphId()));

            List<ConversationNode> threadHistory =
                    nodeRepository.findByParentIdOrderByCreatedAtAsc(job.parentNodeId())
                            .stream()
                            .filter(n -> job.paragraphId().equals(n.getParagraphId()))
                            .filter(n -> n.getStatus() == ConversationNode.Status.COMPLETE)
                            .filter(n -> !n.getId().equals(job.nodeId()))
                            .toList();

            messages = buildParagraphContext(
                    parentNode, targetParagraph, threadHistory, job.question());
        }

        streamResponse(node, messages);
    }

    private List<AiMessage> buildRootContext(Session session, String question) {
        List<AiMessage> messages = new ArrayList<>();
        messages.add(AiMessage.system(
                promptPolicyService.buildCollaborativeRootPrompt(question)
        ));

        List<ConversationNode> history = nodeRepository
                .findBySessionIdOrderByCreatedAtAsc(session.getId())
                .stream()
                .filter(n -> n.getStatus() == ConversationNode.Status.COMPLETE)
                .filter(n -> n.getDepth() == 0)
                .toList();

        int start = Math.max(0, history.size() - 10);
        for (ConversationNode past : history.subList(start, history.size())) {
            if (past.getQuestion() != null && !past.getQuestion().isBlank()) {
                messages.add(AiMessage.user(past.getQuestion()));
            }
            if (past.getFullContent() != null && !past.getFullContent().isBlank()) {
                messages.add(AiMessage.assistant(past.getFullContent()));
            }
        }

        messages.add(AiMessage.user(question));
        return messages;
    }

    private List<AiMessage> buildParagraphContext(
            ConversationNode parentNode,
            Paragraph targetParagraph,
            List<ConversationNode> threadHistory,
            String question) {

        List<AiMessage> messages = new ArrayList<>();
        messages.add(AiMessage.system(
                promptPolicyService.buildCollaborativeParagraphPrompt(
                        parentNode.getFullContent(),
                        targetParagraph.getContent(),
                        question
                )
        ));

        int start = Math.max(0, threadHistory.size() - 10);
        for (ConversationNode past : threadHistory.subList(start, threadHistory.size())) {
            if (past.getQuestion() != null) {
                messages.add(AiMessage.user(past.getQuestion()));
            }
            if (past.getFullContent() != null && !past.getFullContent().isBlank()) {
                messages.add(AiMessage.assistant(past.getFullContent()));
            }
        }

        messages.add(AiMessage.user(question));
        return messages;
    }

    private void streamResponse(ConversationNode node, List<AiMessage> messages) {
        StringBuilder fullContent = new StringBuilder();

        aiProvider.streamResponse(messages)
                .doOnNext(chunk -> {
                    fullContent.append(chunk);
                    centrifugoService.publishTokenChunk(node.getId(), chunk);
                    node.setFullContent(fullContent.toString());
                    nodeRepository.save(node);
                })
                .doOnComplete(() -> {
                    node.setFullContent(fullContent.toString());
                    node.setStatus(ConversationNode.Status.COMPLETE);
                    nodeRepository.save(node);
                    centrifugoService.publishStreamComplete(node.getId());
                    // Invalidate snapshot cache — node is now complete
                    // Next snapshot call will rebuild from PostgreSQL with fresh content
                    snapshotCacheService.invalidate(node.getSession().getId());
                    log.info("Stream complete for node {}", node.getId());
                })
                .doOnError(e -> {
                    log.error("Streaming error for node {}: {}",
                            node.getId(), e.getMessage());
                    node.setFullContent(fullContent.toString());
                    node.setStatus(ConversationNode.Status.COMPLETE);
                    nodeRepository.save(node);
                    centrifugoService.publishStreamComplete(node.getId());
                    snapshotCacheService.invalidate(node.getSession().getId());
                })
                .subscribe();
    }
}
