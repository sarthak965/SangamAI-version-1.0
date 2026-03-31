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
        StreamBlockParser parser = new StreamBlockParser();
        final int[] blockIndex = {0};

        aiProvider.streamResponse(messages)
                .doOnNext(chunk -> {
                    fullContent.append(chunk);
                    centrifugoService.publishTokenChunk(node.getId(), chunk);
                    parser.accept(chunk);

                    List<String> completedBlocks = parser.drainCompletedBlocks();
                    if (!completedBlocks.isEmpty()) {
                        for (String block : completedBlocks) {
                            saveParagraph(node, blockIndex[0], block);
                            blockIndex[0]++;
                        }

                        // Flush fullContent to DB so polling clients see
                        // the growing response even if they missed Centrifugo chunks.
                        node.setFullContent(fullContent.toString());
                        nodeRepository.save(node);
                    }
                })
                .doOnComplete(() -> {
                    List<String> remainingBlocks = parser.finish();
                    for (String block : remainingBlocks) {
                        saveParagraph(node, blockIndex[0], block);
                        blockIndex[0]++;
                    }
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

    private void saveParagraph(ConversationNode node, int index, String content) {
        Paragraph paragraph = Paragraph.builder()
                .node(node)
                .index(index)
                .content(content)
                .build();
        Paragraph saved = paragraphRepository.save(paragraph);
        centrifugoService.publishParagraphReady(
                node.getId(), saved.getId(), index, content);
    }

    private static final class StreamBlockParser {
        private static final int SHORT_INTRO_WORD_LIMIT = 18;
        private static final int SHORT_INTRO_CHAR_LIMIT = 140;

        private final List<String> completedBlocks = new ArrayList<>();
        private final List<RawBlock> pendingBlocks = new ArrayList<>();
        private final StringBuilder currentBlock = new StringBuilder();
        private final StringBuilder currentLine = new StringBuilder();
        private RawBlockType currentType;
        private boolean inCodeFence = false;

        void accept(String chunk) {
            for (int i = 0; i < chunk.length(); i++) {
                char ch = chunk.charAt(i);
                currentLine.append(ch);

                if (ch == '\n') {
                    flushLine();
                }
            }
        }

        List<String> drainCompletedBlocks() {
            List<String> blocks = new ArrayList<>(completedBlocks);
            completedBlocks.clear();
            return blocks;
        }

        List<String> finish() {
            if (currentLine.length() > 0) {
                flushLine();
            }
            flushCurrentBlock();
            resolvePendingBlocks(true);
            return drainCompletedBlocks();
        }

        private void flushLine() {
            String line = currentLine.toString();
            currentLine.setLength(0);

            String trimmed = line.trim();
            if (inCodeFence) {
                currentBlock.append(line);
                if (trimmed.startsWith("```")) {
                    inCodeFence = false;
                    flushCurrentBlock();
                }
                return;
            }

            if (trimmed.startsWith("```")) {
                flushCurrentBlock();
                currentType = RawBlockType.CODE;
                inCodeFence = true;
                currentBlock.append(line);
                return;
            }

            if (trimmed.isEmpty()) {
                flushCurrentBlock();
                return;
            }

            RawBlockType nextType = classifyLine(trimmed);

            if (nextType == RawBlockType.HEADING) {
                flushCurrentBlock();
                currentType = RawBlockType.HEADING;
                currentBlock.append(line);
                flushCurrentBlock();
                return;
            }

            if (currentType == null) {
                currentType = nextType;
                currentBlock.append(line);
                return;
            }

            if (canContinueCurrentBlock(nextType, trimmed, line)) {
                currentBlock.append(line);
                return;
            }

            flushCurrentBlock();
            currentType = nextType;
            currentBlock.append(line);
        }

        private void flushCurrentBlock() {
            String block = normalizeBlock(currentBlock.toString());
            currentBlock.setLength(0);
            RawBlockType blockType = currentType;
            currentType = null;

            if (!block.isEmpty() && blockType != null) {
                pendingBlocks.add(new RawBlock(blockType, block));
                resolvePendingBlocks(false);
            }
        }

        private RawBlockType classifyLine(String trimmed) {
            if (trimmed.matches("#{1,6}\\s+.*")) {
                return RawBlockType.HEADING;
            }
            if (trimmed.matches("[-*+]\\s+.*") || trimmed.matches("\\d+\\.\\s+.*")) {
                return RawBlockType.LIST;
            }
            if (trimmed.startsWith(">")) {
                return RawBlockType.QUOTE;
            }
            return RawBlockType.TEXT;
        }

        private boolean canContinueCurrentBlock(
                RawBlockType nextType, String trimmed, String originalLine) {
            if (currentType == RawBlockType.TEXT) {
                return nextType == RawBlockType.TEXT;
            }
            if (currentType == RawBlockType.LIST) {
                return nextType == RawBlockType.LIST || isListContinuation(originalLine, trimmed);
            }
            if (currentType == RawBlockType.QUOTE) {
                return nextType == RawBlockType.QUOTE;
            }
            return false;
        }

        private boolean isListContinuation(String originalLine, String trimmed) {
            if (trimmed.isEmpty()) {
                return false;
            }
            if (trimmed.matches("[-*+]\\s+.*") || trimmed.matches("\\d+\\.\\s+.*")) {
                return true;
            }
            return Character.isWhitespace(originalLine.charAt(0));
        }

        private String normalizeBlock(String raw) {
            String block = raw.strip();
            if (block.isEmpty()) {
                return "";
            }

            return block.replace("\r\n", "\n");
        }

        private void resolvePendingBlocks(boolean finalPass) {
            int index = 0;

            while (index < pendingBlocks.size()) {
                if (!finalPass && index >= pendingBlocks.size() - 1) {
                    break;
                }

                RawBlock current = pendingBlocks.get(index);

                if (current.type() == RawBlockType.HEADING) {
                    if (index + 1 >= pendingBlocks.size()) {
                        break;
                    }

                    StringBuilder unit = new StringBuilder(current.content());
                    int consumed = 1;

                    RawBlock next = pendingBlocks.get(index + 1);
                    unit.append("\n\n").append(next.content());
                    consumed++;

                    if (shouldMergeThirdBlockAfterHeading(next, index + 2)) {
                        unit.append("\n\n").append(pendingBlocks.get(index + 2).content());
                        consumed++;
                    }

                    completedBlocks.add(unit.toString());
                    pendingBlocks.subList(index, index + consumed).clear();
                    continue;
                }

                if (isShortIntro(current)) {
                    if (index + 1 >= pendingBlocks.size()) {
                        break;
                    }

                    RawBlock next = pendingBlocks.get(index + 1);
                    if (next.type() == RawBlockType.HEADING && index + 2 < pendingBlocks.size()) {
                        StringBuilder unit = new StringBuilder(current.content())
                                .append("\n\n")
                                .append(next.content())
                                .append("\n\n")
                                .append(pendingBlocks.get(index + 2).content());
                        pendingBlocks.subList(index, index + 3).clear();
                        completedBlocks.add(unit.toString());
                        continue;
                    }

                    if (shouldMergeShortIntroWithNext(next)) {
                        String unit = current.content() + "\n\n" + next.content();
                        pendingBlocks.subList(index, index + 2).clear();
                        completedBlocks.add(unit);
                        continue;
                    }
                }

                completedBlocks.add(current.content());
                pendingBlocks.remove(index);
            }

            if (finalPass) {
                for (RawBlock block : pendingBlocks) {
                    completedBlocks.add(block.content());
                }
                pendingBlocks.clear();
            }
        }

        private boolean shouldMergeThirdBlockAfterHeading(RawBlock next, int thirdIndex) {
            if (thirdIndex >= pendingBlocks.size()) {
                return false;
            }

            RawBlock third = pendingBlocks.get(thirdIndex);
            return isShortIntro(next)
                    && (third.type() == RawBlockType.CODE
                    || third.type() == RawBlockType.LIST
                    || third.type() == RawBlockType.QUOTE
                    || third.type() == RawBlockType.TEXT);
        }

        private boolean shouldMergeShortIntroWithNext(RawBlock next) {
            return next.type() == RawBlockType.CODE
                    || next.type() == RawBlockType.LIST
                    || next.type() == RawBlockType.QUOTE;
        }

        private boolean isShortIntro(RawBlock block) {
            if (block.type() != RawBlockType.TEXT) {
                return false;
            }

            String normalized = block.content().replace('\n', ' ').trim();
            int wordCount = normalized.isEmpty() ? 0 : normalized.split("\\s+").length;
            return !normalized.contains(".\n")
                    && wordCount <= SHORT_INTRO_WORD_LIMIT
                    && normalized.length() <= SHORT_INTRO_CHAR_LIMIT;
        }

        private enum RawBlockType {
            TEXT,
            HEADING,
            LIST,
            QUOTE,
            CODE
        }

        private record RawBlock(RawBlockType type, String content) {
        }
    }
}
