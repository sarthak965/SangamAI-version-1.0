import { useEffect, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import Prism from "prismjs";
import "prismjs/components/prism-java";
import "prismjs/components/prism-javascript";
import "prismjs/components/prism-typescript";
import "prismjs/components/prism-jsx";
import "prismjs/components/prism-tsx";
import "prismjs/components/prism-python";
import "prismjs/components/prism-c";
import "prismjs/components/prism-cpp";
import "prismjs/components/prism-csharp";
import "prismjs/components/prism-go";
import "prismjs/components/prism-rust";
import "prismjs/components/prism-sql";
import "prismjs/components/prism-json";
import "prismjs/components/prism-bash";
import { api } from "../lib/api";
import { realtimeManager } from "../lib/realtime";
import type { SoloChatDetailResponse, SoloChatMessageResponse } from "../types";

export default function WorkspaceChatPage({
  token,
  onWorkspaceChanged,
}: {
  token: string;
  onWorkspaceChanged: () => Promise<void>;
}) {
  const navigate = useNavigate();
  const { chatId } = useParams();
  const [chat, setChat] = useState<SoloChatDetailResponse | null>(null);
  const [draft, setDraft] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  const loadChat = async () => {
    if (!chatId) return;
    const data = await api.getSoloChat(token, chatId);
    setChat(data);
  };

  useEffect(() => {
    loadChat().catch((err: Error) => setError(err.message));
  }, [chatId, token]);

  useEffect(() => {
    if (!chatId || !token) return;

    let unsubscribe: (() => void) | null = null;

    const connectToStream = async () => {
      try {
        unsubscribe = await realtimeManager.subscribe<{
          type: "chunk" | "done";
          messageId: string;
          content?: string;
        }>(token, `chat:${chatId}:stream`, (data) => {
          setChat((prevChat) => applyStreamEvent(prevChat, data));
        });
      } catch (err) {
        console.error("Failed to subscribe to chat stream", err);
      }
    };

    void connectToStream();

    return () => {
      if (unsubscribe) unsubscribe();
    };
  }, [chatId, token]);

  const messagesEndRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const isStreaming = chat?.messages.some((m) => m.status === "STREAMING");
    if (isStreaming) {
      messagesEndRef.current?.scrollIntoView({ behavior: "smooth", block: "nearest" });
    }
  }, [chat?.messages]);

  useEffect(() => {
    if (!chatId || !chat?.messages.some((message) => message.status === "STREAMING")) {
      return;
    }

    let active = true;

    const refreshChat = async () => {
      try {
        const latest = await api.getSoloChat(token, chatId);
        if (!active) return;
        setChat((prevChat) => mergeChatDetail(prevChat, latest));
      } catch {
        // Keep the current UI state if a polling attempt fails.
      }
    };

    const interval = window.setInterval(() => {
      void refreshChat();
    }, 1000);

    void refreshChat();

    return () => {
      active = false;
      window.clearInterval(interval);
    };
  }, [chatId, chat?.messages, token]);

  useEffect(() => {
    autoSizeTextarea(textareaRef.current, 48, 120);
  }, [draft]);

  const send = async () => {
    if (!chatId || !draft.trim()) return;
    setBusy(true);
    setError(null);
    try {
      const updated = await api.sendSoloMessage(token, chatId, draft.trim());
      setChat((prevChat) => mergeChatDetail(prevChat, updated));
      setDraft("");
      await onWorkspaceChanged();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to send message");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="workspace-chat-page">
      <div className="workspace-chat-header">
        <div>
          <h1>{chat?.title ?? "Personal Chat"}</h1>
          <p>
            {chat?.project
              ? `Project context: ${chat.project.name}`
              : "Personal standalone chat"}
          </p>
        </div>
        <button className="btn btn-secondary btn-sm" type="button" onClick={() => navigate("/app")}>
          Back Home
        </button>
      </div>

      {error && <div className="error-banner">{error}</div>}

      <div className="workspace-chat-thread">
        {!chat ? (
          <div className="solo-empty">
            <h3>Loading chat...</h3>
          </div>
        ) : chat.messages.length === 0 ? (
          <div className="solo-empty">
            <h3>This chat is empty</h3>
            <p>Send the first message to start the conversation.</p>
          </div>
        ) : (
          chat.messages.map((message) => (
            <WorkspaceMessage key={message.id} message={message} />
          ))
        )}
        <div ref={messagesEndRef} />
      </div>

      <div className="chat-composer">
        <form
          className="composer-box"
          onSubmit={(event) => {
            event.preventDefault();
            void send();
          }}
        >
          <textarea
            ref={textareaRef}
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
            placeholder="Message SangamAI..."
            disabled={busy}
            rows={1}
            onKeyDown={(event) => {
              if (event.key === "Enter" && !event.shiftKey) {
                event.preventDefault();
                void send();
              }
            }}
          />
          <button className="btn btn-primary" type="submit" disabled={busy || !draft.trim()}>
            {busy ? "..." : "^"}
          </button>
        </form>
      </div>
    </div>
  );
}

function WorkspaceMessage({ message }: { message: SoloChatMessageResponse }) {
  const isAssistant = message.role === "ASSISTANT";
  const isStreaming = message.status === "STREAMING";

  return (
    <div className={`chat-message ${isAssistant ? "assistant-turn" : "user-turn"}`}>
      <div className="chat-message-header">
        <div className={`chat-avatar ${isAssistant ? "ai" : "user"}`}>
          {isAssistant ? "AI" : "Y"}
        </div>
        <span className="chat-sender">{isAssistant ? "SangamAI" : "You"}</span>
      </div>
      {isAssistant ? (
        <div className="ai-response">
          {message.content === "" && isStreaming ? (
            <div className="streaming-indicator">
              <span className="dot" />
              <span className="dot" />
              <span className="dot" />
            </div>
          ) : (
            <MarkdownBlock content={message.content} />
          )}
        </div>
      ) : (
        <div className="workspace-user-bubble">{message.content}</div>
      )}
    </div>
  );
}

function applyStreamEvent(
  prevChat: SoloChatDetailResponse | null,
  data: { type: "chunk" | "done"; messageId: string; content?: string },
): SoloChatDetailResponse | null {
  if (!prevChat) return prevChat;

  const messageIndex = prevChat.messages.findIndex((msg) => msg.id === data.messageId);

  if (messageIndex === -1) {
    const streamedMessage: SoloChatMessageResponse = {
      id: data.messageId,
      role: "ASSISTANT",
      status: data.type === "done" ? "COMPLETE" : "STREAMING",
      content: data.type === "chunk" ? (data.content ?? "") : "",
      createdAt: new Date().toISOString(),
    };

    return {
      ...prevChat,
      messages: [...prevChat.messages, streamedMessage],
    };
  }

  const messages = [...prevChat.messages];
  const existing = messages[messageIndex];
  messages[messageIndex] =
    data.type === "chunk"
      ? { ...existing, status: "STREAMING", content: existing.content + (data.content ?? "") }
      : { ...existing, status: "COMPLETE" };

  return {
    ...prevChat,
    messages,
  };
}

function mergeChatDetail(
  prevChat: SoloChatDetailResponse | null,
  incoming: SoloChatDetailResponse,
): SoloChatDetailResponse {
  if (!prevChat || prevChat.id !== incoming.id) {
    return incoming;
  }

  const previousMessagesById = new Map(prevChat.messages.map((message) => [message.id, message]));
  const mergedMessages = incoming.messages.map((message) => {
    const previous = previousMessagesById.get(message.id);
    if (!previous) return message;

    const mergedContent =
      previous.content.length > message.content.length ? previous.content : message.content;
    const mergedStatus: SoloChatMessageResponse["status"] =
      previous.status === "COMPLETE" || message.status === "COMPLETE"
        ? "COMPLETE"
        : "STREAMING";

    return {
      ...message,
      content: mergedContent,
      status: mergedStatus,
    };
  });

  for (const previous of prevChat.messages) {
    if (!mergedMessages.some((message) => message.id === previous.id)) {
      mergedMessages.push(previous);
    }
  }

  mergedMessages.sort((a, b) => {
    const timeDiff =
      new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime();
    if (timeDiff !== 0) return timeDiff;
    return a.id.localeCompare(b.id);
  });

  return {
    ...incoming,
    messages: mergedMessages,
  };
}

function MarkdownBlock({ content }: { content: string }) {
  return (
    <ReactMarkdown
      remarkPlugins={[remarkGfm]}
      components={{
        h1: ({ children }) => <h1 className="ai-heading ai-heading-1">{children}</h1>,
        h2: ({ children }) => <h2 className="ai-heading ai-heading-2">{children}</h2>,
        h3: ({ children }) => <h3 className="ai-heading ai-heading-3">{children}</h3>,
        h4: ({ children }) => <h4 className="ai-heading ai-heading-4">{children}</h4>,
        h5: ({ children }) => <h5 className="ai-heading ai-heading-5">{children}</h5>,
        h6: ({ children }) => <h6 className="ai-heading ai-heading-6">{children}</h6>,
        p: ({ children }) => <p className="ai-text-block">{children}</p>,
        ul: ({ children }) => <ul className="ai-list ai-list-unordered">{children}</ul>,
        ol: ({ children }) => <ol className="ai-list ai-list-ordered">{children}</ol>,
        blockquote: ({ children }) => <blockquote className="ai-blockquote">{children}</blockquote>,
        a: ({ children, href }) => <a className="ai-link" href={href} rel="noreferrer" target="_blank">{children}</a>,
        code: ({ children, className }) => {
          const value = String(children).replace(/\n$/, "");
          const language = normalizeLanguage(className?.replace("language-", "") ?? "text");
          const isBlock = className != null || value.includes("\n");
          if (!isBlock) return <code className="ai-inline-code">{children}</code>;
          const highlighted = highlightCode(value, language);
          return (
            <div className="ai-code-block">
              <div className="ai-code-header">
                <span>{language}</span>
              </div>
              <pre>
                <code className={`language-${language}`} dangerouslySetInnerHTML={{ __html: highlighted }} />
              </pre>
            </div>
          );
        },
        pre: ({ children }) => <>{children}</>,
      }}
    >
      {content}
    </ReactMarkdown>
  );
}

function highlightCode(code: string, language: string): string {
  const grammar = Prism.languages[language] ?? Prism.languages.plain ?? Prism.languages.text;
  return Prism.highlight(code, grammar, language);
}

function normalizeLanguage(language: string): string {
  const normalized = language.toLowerCase();
  switch (normalized) {
    case "js":
      return "javascript";
    case "ts":
      return "typescript";
    case "py":
      return "python";
    case "sh":
    case "shell":
    case "zsh":
    case "powershell":
    case "ps1":
      return "bash";
    case "cs":
      return "csharp";
    default:
      return normalized;
  }
}

function autoSizeTextarea(
  element: HTMLTextAreaElement | null,
  minHeight: number,
  maxHeight: number,
) {
  if (!element) return;
  element.style.height = `${minHeight}px`;
  const nextHeight = Math.min(element.scrollHeight, maxHeight);
  element.style.height = `${Math.max(minHeight, nextHeight)}px`;
  element.style.overflowY = element.scrollHeight > maxHeight ? "auto" : "hidden";
}
