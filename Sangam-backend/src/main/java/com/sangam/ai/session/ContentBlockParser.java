package com.sangam.ai.session;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ContentBlockParser {

    public List<String> split(String content) {
        StreamingParser parser = new StreamingParser();
        parser.accept(content == null ? "" : content);
        return parser.finish();
    }

    public static final class StreamingParser {
        private static final int SHORT_INTRO_WORD_LIMIT = 18;
        private static final int SHORT_INTRO_CHAR_LIMIT = 140;

        private final List<String> completedBlocks = new ArrayList<>();
        private final List<RawBlock> pendingBlocks = new ArrayList<>();
        private final StringBuilder currentBlock = new StringBuilder();
        private final StringBuilder currentLine = new StringBuilder();
        private RawBlockType currentType;
        private boolean inCodeFence = false;

        public void accept(String chunk) {
            if (chunk == null || chunk.isEmpty()) {
                return;
            }

            for (int i = 0; i < chunk.length(); i++) {
                char ch = chunk.charAt(i);
                currentLine.append(ch);

                if (ch == '\n') {
                    flushLine();
                }
            }
        }

        public List<String> drainCompletedBlocks() {
            List<String> blocks = new ArrayList<>(completedBlocks);
            completedBlocks.clear();
            return blocks;
        }

        public List<String> finish() {
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
            return !originalLine.isEmpty() && Character.isWhitespace(originalLine.charAt(0));
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
