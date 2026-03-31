package com.sangam.ai.workspace;

import com.sangam.ai.ai.AiMessage;
import com.sangam.ai.ai.AiProvider;
import com.sangam.ai.ai.PromptPolicyService;
import com.sangam.ai.realtime.CentrifugoService;
import com.sangam.ai.user.User;
import com.sangam.ai.workspace.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceService {

    private final ProjectRepository projectRepository;
    private final SoloChatRepository soloChatRepository;
    private final SoloChatMessageRepository soloChatMessageRepository;
    private final AiProvider aiProvider;
    private final PromptPolicyService promptPolicyService;
    private final CentrifugoService centrifugoService;
    private final TransactionTemplate transactionTemplate;

    public List<ProjectResponse> listProjects(User user) {
        return projectRepository.findByOwnerOrderByUpdatedAtDesc(user)
                .stream()
                .map(ProjectResponse::from)
                .toList();
    }

    @Transactional
    public ProjectResponse createProject(ProjectUpsertRequest request, User user) {
        Project project = Project.builder()
                .owner(user)
                .name(request.name().trim())
                .description(normalizeNullable(request.description()))
                .systemInstructions(normalizeText(request.systemInstructions()))
                .knowledgeContext(normalizeText(request.knowledgeContext()))
                .build();

        return ProjectResponse.from(projectRepository.save(project));
    }

    @Transactional
    public ProjectResponse updateProject(UUID projectId, ProjectUpsertRequest request, User user) {
        Project project = getOwnedProject(projectId, user);
        project.setName(request.name().trim());
        project.setDescription(normalizeNullable(request.description()));
        project.setSystemInstructions(normalizeText(request.systemInstructions()));
        project.setKnowledgeContext(normalizeText(request.knowledgeContext()));
        return ProjectResponse.from(projectRepository.save(project));
    }

    public List<SoloChatSummaryResponse> listChats(User user) {
        return soloChatRepository.findByOwnerOrderByPinnedDescUpdatedAtDesc(user)
                .stream()
                .map(this::toSummary)
                .toList();
    }

    public List<SoloChatSummaryResponse> listRecentChats(User user, int limit) {
        return listChats(user).stream().limit(Math.max(1, limit)).toList();
    }

    @Transactional
    public SoloChatDetailResponse createChat(CreateSoloChatRequest request, User user) {
        Project project = request.projectId() != null
                ? getOwnedProject(request.projectId(), user)
                : null;

        SoloChat chat = SoloChat.builder()
                .owner(user)
                .project(project)
                .title(normalizeTitle(request.title()))
                .build();

        SoloChat saved = soloChatRepository.save(chat);
        return toDetail(saved);
    }

    public SoloChatDetailResponse getChat(UUID chatId, User user) {
        return toDetail(getOwnedChat(chatId, user));
    }

    @Transactional
    public SoloChatDetailResponse updateChat(UUID chatId, UpdateSoloChatRequest request, User user) {
        SoloChat chat = getOwnedChat(chatId, user);

        if (request.title() != null) {
            chat.setTitle(normalizeTitle(request.title()));
        }
        if (request.pinned() != null) {
            chat.setPinned(request.pinned());
        }
        if (request.projectId() != null) {
            chat.setProject(getOwnedProject(request.projectId(), user));
        }

        chat.setUpdatedAt(Instant.now());
        return toDetail(soloChatRepository.save(chat));
    }

    @Transactional
    public void removeChat(UUID chatId, User user) {
        SoloChat chat = getOwnedChat(chatId, user);
        soloChatRepository.delete(chat);
    }

    @Transactional
    public SoloChatDetailResponse sendMessage(UUID chatId, SendSoloMessageRequest request, User user) {
        SoloChat chat = getOwnedChat(chatId, user);
        String content = request.content().trim();

        SoloChatMessage userMessage = SoloChatMessage.builder()
                .chat(chat)
                .role(SoloChatMessage.Role.USER)
                .status(SoloChatMessage.Status.COMPLETE)
                .content(content)
                .build();
        soloChatMessageRepository.save(userMessage);

        if (isDefaultTitle(chat.getTitle())) {
            chat.setTitle(deriveChatTitle(content));
        }

        SoloChatMessage assistantMessage = SoloChatMessage.builder()
                .chat(chat)
                .role(SoloChatMessage.Role.ASSISTANT)
                .status(SoloChatMessage.Status.STREAMING)
                .content("")
                .build();
        soloChatMessageRepository.save(assistantMessage);

        chat.setUpdatedAt(Instant.now());
        soloChatRepository.save(chat);

        List<AiMessage> messages = buildAiMessages(chat, content);
        streamAssistantReply(chat.getId(), assistantMessage.getId(), messages);

        return toDetail(chat);
    }

    private void streamAssistantReply(UUID chatId, UUID messageId, List<AiMessage> messages) {
        StringBuilder fullReply = new StringBuilder();

        aiProvider.streamResponse(messages)
                .doOnNext(chunk -> {
                    fullReply.append(chunk);
                    updateStreamingMessageContent(messageId, fullReply.toString());
                    centrifugoService.publishSoloChatChunk(chatId, messageId, chunk);
                })
                .doOnError(error -> {
                    log.error("Streaming failed for solo chat {}", chatId, error);
                    finalizeMessage(messageId, fullReply.toString(), SoloChatMessage.Status.COMPLETE);
                    centrifugoService.publishSoloChatComplete(chatId, messageId);
                })
                .doOnComplete(() -> {
                    finalizeMessage(messageId, fullReply.toString(), SoloChatMessage.Status.COMPLETE);
                    centrifugoService.publishSoloChatComplete(chatId, messageId);
                })
                .subscribe();
    }

    private void updateStreamingMessageContent(UUID messageId, String content) {
        transactionTemplate.executeWithoutResult(s -> {
            soloChatMessageRepository.findById(messageId).ifPresent(message -> {
                message.setContent(content);
                message.setStatus(SoloChatMessage.Status.STREAMING);
                soloChatMessageRepository.save(message);
            });
        });
    }

    private void finalizeMessage(UUID messageId, String content, SoloChatMessage.Status status) {
        transactionTemplate.executeWithoutResult(s -> {
            soloChatMessageRepository.findById(messageId).ifPresent(message -> {
                message.setContent(content);
                message.setStatus(status);
                soloChatMessageRepository.save(message);
            });
        });
    }

    private List<AiMessage> buildAiMessages(SoloChat chat, String latestUserMessage) {
        List<AiMessage> messages = new ArrayList<>();
        messages.add(AiMessage.system(
                promptPolicyService.buildPersonalSystemPrompt(chat.getProject(), latestUserMessage)
        ));

        List<SoloChatMessage> history = soloChatMessageRepository.findByChatIdOrderByCreatedAtAsc(chat.getId());
        for (SoloChatMessage message : history) {
            if (message.getStatus() == SoloChatMessage.Status.STREAMING) continue;
            
            if (message.getRole() == SoloChatMessage.Role.USER) {
                messages.add(AiMessage.user(message.getContent()));
            } else if (message.getRole() == SoloChatMessage.Role.ASSISTANT) {
                messages.add(AiMessage.assistant(message.getContent()));
            }
        }

        if (history.isEmpty()
                || history.get(history.size() - 1).getRole() != SoloChatMessage.Role.USER
                || !history.get(history.size() - 1).getContent().equals(latestUserMessage)) {
            messages.add(AiMessage.user(latestUserMessage));
        }

        return messages;
    }

    private SoloChatSummaryResponse toSummary(SoloChat chat) {
        SoloChatMessage lastMessage = soloChatMessageRepository
                .findTopByChatIdOrderByCreatedAtDesc(chat.getId())
                .orElse(null);
        return SoloChatSummaryResponse.from(chat, chat.getProject(), lastMessage);
    }

    private SoloChatDetailResponse toDetail(SoloChat chat) {
        List<SoloChatMessageResponse> messages = soloChatMessageRepository
                .findByChatIdOrderByCreatedAtAsc(chat.getId())
                .stream()
                .map(SoloChatMessageResponse::from)
                .toList();

        return new SoloChatDetailResponse(
                chat.getId(),
                chat.getTitle(),
                chat.isPinned(),
                chat.getProject() != null ? ProjectResponse.from(chat.getProject()) : null,
                chat.getCreatedAt(),
                chat.getUpdatedAt(),
                messages
        );
    }

    private Project getOwnedProject(UUID projectId, User user) {
        return projectRepository.findByIdAndOwner(projectId, user)
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));
    }

    private SoloChat getOwnedChat(UUID chatId, User user) {
        return soloChatRepository.findByIdAndOwner(chatId, user)
                .orElseThrow(() -> new IllegalArgumentException("Chat not found"));
    }

    private String normalizeTitle(String title) {
        if (title == null || title.isBlank()) {
            return "New Chat";
        }
        String trimmed = title.trim();
        return trimmed.length() > 255 ? trimmed.substring(0, 255) : trimmed;
    }

    private boolean isDefaultTitle(String title) {
        return title == null || title.isBlank() || "New Chat".equalsIgnoreCase(title.trim());
    }

    private String deriveChatTitle(String prompt) {
        String normalized = prompt.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 60) {
            return normalized;
        }
        return normalized.substring(0, 60) + "...";
    }

    private String normalizeNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.trim();
    }
}
