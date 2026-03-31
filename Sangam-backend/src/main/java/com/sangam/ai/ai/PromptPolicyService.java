package com.sangam.ai.ai;

import com.sangam.ai.workspace.Project;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PromptPolicyService {

    private final PromptIntentClassifier intentClassifier;

    public PromptPolicyService(PromptIntentClassifier intentClassifier) {
        this.intentClassifier = intentClassifier;
    }

    public String buildPersonalSystemPrompt(Project project, String userMessage) {
        PromptIntent intent = intentClassifier.classify(userMessage);

        StringBuilder prompt = new StringBuilder("""
                You are SangamAI, a personal AI assistant.
                Write clear, well-structured markdown.
                Prefer direct prose, meaningful paragraphs, bullets, and tables when they genuinely improve clarity.
                Use headings only when they help structure the answer.
                If the user asks for a diagram, you may use Mermaid.
                Never force code, pseudo-code, shell commands, SQL, JSON, YAML, or configuration snippets into an answer unless they are explicitly requested or clearly required by the task.
                """);

        appendIntentPolicy(prompt, intent);

        if (project != null) {
            prompt.append("\n\nThis chat belongs to a persistent project workspace.");
            prompt.append("\nProject name: ").append(project.getName());

            if (hasText(project.getDescription())) {
                prompt.append("\nProject description:\n").append(project.getDescription().trim());
            }
            if (hasText(project.getSystemInstructions())) {
                prompt.append("\nProject instructions:\n").append(project.getSystemInstructions().trim());
            }
            if (hasText(project.getKnowledgeContext())) {
                prompt.append("\nProject knowledge:\n").append(project.getKnowledgeContext().trim());
            }
        }

        return prompt.toString();
    }

    public String buildCollaborativeRootPrompt(String question) {
        PromptIntent intent = intentClassifier.classify(question);

        StringBuilder prompt = new StringBuilder("""
                You are a collaborative AI assistant in SangamAI, a platform where teams have shared AI conversations together in real time.
                Write clean, well-structured markdown.
                Group related explanation into meaningful paragraphs instead of one-sentence fragments.
                Use headings or bullet points only when they improve clarity.
                If the user asks for a diagram, you may use Mermaid.
                Never force code, pseudo-code, shell commands, SQL, JSON, YAML, or configuration snippets into an answer unless they are explicitly requested or clearly required by the task.
                """);

        appendIntentPolicy(prompt, intent);
        return prompt.toString();
    }

    public String buildCollaborativeParagraphPrompt(
            String fullSessionContext,
            String paragraphContent,
            String question) {

        PromptIntent intent = intentClassifier.classify(
                question,
                List.of(paragraphContent, summarizeContext(fullSessionContext))
        );

        StringBuilder prompt = new StringBuilder(String.format("""
                You are a collaborative AI assistant in SangamAI.

                Here is the full AI response given in this session:
                --- BEGIN SESSION CONTEXT ---
                %s
                --- END SESSION CONTEXT ---

                The user is asking about this specific paragraph:
                --- BEGIN PARAGRAPH ---
                %s
                --- END PARAGRAPH ---

                Answer focused on that paragraph.
                Write clean, well-structured markdown.
                Group related explanation into meaningful paragraphs instead of one-sentence fragments.
                Use headings or bullet points only when they improve clarity.
                If the user asks for a diagram, you may use Mermaid.
                Never force code, pseudo-code, shell commands, SQL, JSON, YAML, or configuration snippets into an answer unless they are explicitly requested or clearly required by the task.
                """,
                fullSessionContext,
                paragraphContent
        ));

        appendIntentPolicy(prompt, intent);
        return prompt.toString();
    }

    private void appendIntentPolicy(StringBuilder prompt, PromptIntent intent) {
        switch (intent) {
            case TECHNICAL -> prompt.append("""

                    This request appears technical.
                    You may provide code when it is useful, but still prefer the minimum amount of code needed.
                    When you include code, use fenced code blocks with an appropriate language tag.
                    Do not include code unless it materially helps solve the user's request.
                    """);
            case AMBIGUOUS -> prompt.append("""

                    This request is ambiguous between technical and non-technical.
                    Default to explanation first.
                    Only include code if the user explicitly asks for it or the task cannot be answered well without it.
                    If code is not necessary, use prose, bullets, tables, or diagrams instead.
                    """);
            case NON_TECHNICAL -> prompt.append("""

                    This request is non-technical.
                    Do not generate programming code, pseudo-code, shell commands, SQL, JSON, YAML, schemas, or config snippets unless the user explicitly asks for them.
                    For history, politics, biography, religion, philosophy, culture, essays, or general explanation, answer in plain language.
                    Never represent a person, ideology, historical event, or social topic as a function, variable, class, script, or code example.
                    Prefer prose, bullets, comparison tables, timelines, or diagrams only when they genuinely improve understanding.
                    """);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String summarizeContext(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.length() <= 1200 ? value : value.substring(0, 1200);
    }
}
