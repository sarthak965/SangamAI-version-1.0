package com.sangam.ai.ai;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class PromptIntentClassifier {

    private static final List<String> STRONG_TECHNICAL_KEYWORDS = List.of(
            "python", "java", "javascript", "typescript", "tsx", "jsx", "react",
            "spring boot", "spring", "sql", "postgres", "mysql", "redis",
            "api", "backend", "frontend", "full stack", "docker", "kubernetes",
            "html", "css", "json", "yaml", "xml", "regex", "algorithm",
            "data structure", "leetcode", "bug", "stack trace", "exception",
            "compile", "build failed", "refactor", "debug", "endpoint",
            "function", "class", "interface", "repository", "entity", "schema",
            "query", "code", "program", "script", "implementation", "sdk"
    );

    private static final List<String> SOFT_TECHNICAL_KEYWORDS = List.of(
            "table", "diagram", "flow", "architecture", "system design",
            "cli", "terminal", "command", "markdown", "mermaid",
            "performance", "latency", "cache", "thread", "async", "streaming"
    );

    private static final List<String> EXPLICIT_CODE_REQUEST_PHRASES = List.of(
            "write code", "give code", "show code", "generate code",
            "implement", "build a function", "write a function", "write a class",
            "create a script", "give me a script", "provide code",
            "code example", "with code", "in python", "in java", "in javascript",
            "in typescript", "in c++", "in c#", "in go", "in rust", "in sql"
    );

    private static final List<String> STRONG_NON_TECHNICAL_KEYWORDS = List.of(
            "who was", "tell me about", "history", "historical", "biography",
            "essay", "speech", "poem", "philosophy", "religion", "politics",
            "leader", "freedom fighter", "explain in simple words", "write about",
            "easy words", "simple words", "summarize this person", "life of",
            "importance of", "culture", "society", "ideology"
    );

    private static final Pattern CODE_FENCE_PATTERN = Pattern.compile("```");
    private static final Pattern CODE_SIGNATURE_PATTERN = Pattern.compile(
            "(?s).*(\\bdef\\s+\\w+\\s*\\(|\\bclass\\s+\\w+\\b|\\bfunction\\s*\\(|\\bSELECT\\b|\\bINSERT\\b|\\{\\s*\\n|=>|::|public\\s+class|<\\w+>).*$"
    );

    public PromptIntent classify(String message) {
        return classify(message, List.of());
    }

    public PromptIntent classify(String message, List<String> contextualSignals) {
        String normalized = normalize(message);
        int score = 0;

        if (normalized.isBlank()) {
            return PromptIntent.AMBIGUOUS;
        }

        if (containsAny(normalized, EXPLICIT_CODE_REQUEST_PHRASES)) {
            score += 7;
        }

        score += keywordScore(normalized, STRONG_TECHNICAL_KEYWORDS, 3);
        score += keywordScore(normalized, SOFT_TECHNICAL_KEYWORDS, 1);
        score -= keywordScore(normalized, STRONG_NON_TECHNICAL_KEYWORDS, 2);

        if (CODE_FENCE_PATTERN.matcher(normalized).find()) {
            score += 5;
        }
        if (CODE_SIGNATURE_PATTERN.matcher(normalized).matches()) {
            score += 5;
        }

        for (String signal : contextualSignals) {
            String context = normalize(signal);
            if (context.isBlank()) {
                continue;
            }
            score += keywordScore(context, STRONG_TECHNICAL_KEYWORDS, 1);
            if (CODE_FENCE_PATTERN.matcher(context).find()
                    || CODE_SIGNATURE_PATTERN.matcher(context).matches()) {
                score += 3;
            }
        }

        if (containsAny(normalized, "compare", "explain", "summary", "summarize")
                && score > 0 && !containsAny(normalized, EXPLICIT_CODE_REQUEST_PHRASES)) {
            score -= 1;
        }

        if (score >= 5) {
            return PromptIntent.TECHNICAL;
        }
        if (score <= 0) {
            return PromptIntent.NON_TECHNICAL;
        }
        return PromptIntent.AMBIGUOUS;
    }

    public boolean explicitlyRequestsCode(String message) {
        String normalized = normalize(message);
        return containsAny(normalized, EXPLICIT_CODE_REQUEST_PHRASES)
                || CODE_FENCE_PATTERN.matcher(normalized).find()
                || CODE_SIGNATURE_PATTERN.matcher(normalized).matches();
    }

    private int keywordScore(String normalized, List<String> keywords, int weight) {
        int score = 0;
        for (String keyword : keywords) {
            if (normalized.contains(keyword)) {
                score += weight;
            }
        }
        return score;
    }

    private boolean containsAny(String normalized, List<String> phrases) {
        for (String phrase : phrases) {
            if (normalized.contains(phrase)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAny(String normalized, String... phrases) {
        for (String phrase : phrases) {
            if (normalized.contains(phrase)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).trim();
    }
}
