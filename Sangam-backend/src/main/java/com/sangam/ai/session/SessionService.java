package com.sangam.ai.session;

import com.sangam.ai.ai.AiMessage;
import com.sangam.ai.ai.AiProvider;
import com.sangam.ai.environment.EnvironmentMember;
import com.sangam.ai.environment.EnvironmentMemberRepository;
import com.sangam.ai.realtime.CentrifugoService;
import com.sangam.ai.user.User;
import com.sangam.ai.environment.EnvironmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    private final SessionRepository sessionRepository;
    private final ConversationNodeRepository nodeRepository;
    private final ParagraphRepository paragraphRepository;
    private final EnvironmentRepository environmentRepository;
    private final EnvironmentMemberRepository memberRepository;
    private final AiProvider aiProvider;
    private final CentrifugoService centrifugoService;

    @Transactional
    public Session createSession(UUID environmentId, String title, User user) {
        var environment = environmentRepository.findById(environmentId)
                .orElseThrow(() -> new IllegalArgumentException("Environment not found"));

        assertCanInteractWithAi(environmentId, user);

        Session session = Session.builder()
                .environment(environment)
                .createdBy(user)
                .title(title)
                .status(Session.Status.OPEN)
                .build();

        return sessionRepository.save(session);
    }

    /**
     * The core method of Stage 2.
     * Receives a question, creates a ConversationNode, streams the
     * AI response, publishes each token to Centrifugo, detects
     * paragraph boundaries, and saves paragraphs progressively.
     *
     * Notice this method is NOT @Transactional — the streaming
     * happens over several seconds and you don't want a database
     * transaction held open that entire time.
     */
    public UUID ask(UUID sessionId, String question, User user) {

        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found"));

        assertCanInteractWithAi(session.getEnvironment().getId(), user);

        // Create the node immediately and return its ID.
        // The client subscribes to this node's channel and
        // starts receiving tokens as they arrive.
        ConversationNode node = ConversationNode.builder()
                .session(session)
                .parent(null)       // root node for now — Stage 3 adds child nodes
                .depth(0)
                .question(question)
                .askedBy(user)
                .fullContent("")
                .status(ConversationNode.Status.STREAMING)
                .build();

        ConversationNode savedNode = nodeRepository.save(node);

        // Kick off streaming asynchronously — we don't block here.
        // The client is already listening on the Centrifugo channel.
        streamAiResponse(savedNode, question);

        return savedNode.getId();
    }

    private void streamAiResponse(ConversationNode node, String question) {
        List<AiMessage> messages = List.of(
                AiMessage.system("""
                    You are a collaborative AI assistant in SangamAI, 
                    a platform where teams have shared AI conversations.
                    Give clear, well-structured responses with natural 
                    paragraph breaks between distinct ideas.
                    """),
                AiMessage.user(question)
        );

        // These hold state as tokens arrive
        StringBuilder fullContent = new StringBuilder();
        StringBuilder currentParagraph = new StringBuilder();
        final int[] paragraphIndex = {0};  // array trick to mutate in lambda

        aiProvider.streamResponse(messages)
                .doOnNext(chunk -> {
                    // 1. Append to full content buffer
                    fullContent.append(chunk);
                    currentParagraph.append(chunk);

                    // 2. Publish this chunk to Centrifugo — all members see it live
                    centrifugoService.publishTokenChunk(node.getId(), chunk);

                    // 3. Check if this chunk contains a paragraph boundary
                    if (isParagraphBoundary(currentParagraph.toString())) {
                        String paraContent = currentParagraph.toString().trim();
                        if (!paraContent.isEmpty()) {
                            saveParagraph(node, paragraphIndex[0], paraContent);
                            paragraphIndex[0]++;
                        }
                        currentParagraph.setLength(0); // reset buffer
                    }
                })
                .doOnComplete(() -> {
                    // Save any remaining content as the final paragraph
                    String remaining = currentParagraph.toString().trim();
                    if (!remaining.isEmpty()) {
                        saveParagraph(node, paragraphIndex[0], remaining);
                    }

                    // Mark node complete and save full content
                    node.setFullContent(fullContent.toString());
                    node.setStatus(ConversationNode.Status.COMPLETE);
                    nodeRepository.save(node);

                    // Tell all clients the stream is finished
                    centrifugoService.publishStreamComplete(node.getId());
                    log.info("Stream complete for node {}", node.getId());
                })
                .doOnError(e -> {
                    log.error("AI streaming error for node {}: {}", node.getId(), e.getMessage());
                    node.setStatus(ConversationNode.Status.COMPLETE);
                    node.setFullContent(fullContent.toString());
                    nodeRepository.save(node);
                    centrifugoService.publishStreamComplete(node.getId());
                })
                .subscribe(); // this triggers the stream — non-blocking
    }

    private void saveParagraph(ConversationNode node, int index, String content) {
        Paragraph paragraph = Paragraph.builder()
                .node(node)
                .index(index)
                .content(content)
                .build();
        Paragraph saved = paragraphRepository.save(paragraph);

        // Notify all clients that this paragraph is ready and interactive
        centrifugoService.publishParagraphReady(
                node.getId(), saved.getId(), index, content);

        log.info("Saved paragraph {} for node {}", index, node.getId());
    }

    private boolean isParagraphBoundary(String text) {
        // A paragraph ends when we see a double newline
        // or a markdown heading
        return text.contains("\n\n") || text.matches("(?s).*\n#{1,6} .*");
    }

    private void assertCanInteractWithAi(UUID environmentId, User user) {
        EnvironmentMember member = memberRepository
                .findByEnvironmentIdAndUserId(environmentId, user.getId())
                .orElseThrow(() -> new SecurityException("You are not a member"));

        if (!member.isCanInteractWithAi()) {
            throw new SecurityException("You don't have permission to interact with AI");
        }
    }
}