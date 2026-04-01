import { useEffect, useMemo, useRef, useState } from "react";
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
import type {
  ConversationNodeDto,
  CurrentUser,
  EnvironmentEventMemberUpdated,
  EnvironmentResponse,
  MemberResponse,
  NodeStreamEvent,
  ParagraphDto,
  SessionEventChildNodeCreated,
  SessionEventRootNodeCreated,
  SessionListItem,
  SessionSnapshotDto,
  StreamingNodeState,
} from "../types";

const MAX_THREAD_DEPTH = 3;

type ViewState =
  | { type: "root"; title: string }
  | { type: "paragraph"; nodeId: string; blockIndex: number }
  | { type: "node"; nodeId: string; question: string };

type RenderableBlock =
  | { type: "text"; content: string }
  | { type: "code"; content: string; language: string | null };

export default function SessionPage({
  token,
  me,
}: {
  token: string;
  me: CurrentUser;
}) {
  const navigate = useNavigate();
  const { environmentId, sessionId } = useParams();
  const [environment, setEnvironment] = useState<EnvironmentResponse | null>(null);
  const [members, setMembers] = useState<MemberResponse[]>([]);
  const [sessions, setSessions] = useState<SessionListItem[]>([]);
  const [snapshot, setSnapshot] = useState<SessionSnapshotDto | null>(null);
  const [streamingNodes, setStreamingNodes] = useState<Record<string, StreamingNodeState>>({});
  const [busyKey, setBusyKey] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [rootQuestion, setRootQuestion] = useState("");
  const [viewStack, setViewStack] = useState<ViewState[]>([{ type: "root", title: "Root" }]);

  const currentView = viewStack[viewStack.length - 1];
  const sessionUnsub = useRef<(() => void) | null>(null);
  const envUnsub = useRef<(() => void) | null>(null);
  const nodeUnsubs = useRef<Record<string, () => void>>({});
  const chatContainerRef = useRef<HTMLDivElement>(null);
  const chatBottomRef = useRef<HTMLDivElement>(null);
  const hasScrolledToBottom = useRef(false);
  const refreshInFlight = useRef(false);
  const composerTextareaRef = useRef<HTMLTextAreaElement>(null);
  const viewScrollPositions = useRef<Record<string, number>>({ root: 0 });

  const selectedSession = useMemo(
    () => sessions.find((session) => session.sessionId === sessionId) ?? null,
    [sessions, sessionId],
  );

  const currentMember = members.find((member) => member.username === me.username) ?? null;
  const canAskRoot = currentMember?.role === "OWNER" || currentMember?.role === "CO_HOST";
  const canAskParagraph = canAskRoot || Boolean(currentMember?.canInteractWithAi);

  const loadAll = async () => {
    if (!environmentId || !sessionId) return;
    const [envs, mems, sess, snap] = await Promise.all([
      api.listEnvironments(token),
      api.getMembers(token, environmentId),
      api.listSessions(token, environmentId),
      api.getSnapshot(token, sessionId),
    ]);
    setEnvironment(envs.find((env) => env.id === environmentId) ?? null);
    setMembers(mems);
    setSessions(sess);
    setSnapshot(snap);
    setStreamingNodes((current) => reconcileStreamingNodes(current, snap));
  };

  const refreshSnapshot = async () => {
    if (!sessionId) return;
    if (refreshInFlight.current) return;
    refreshInFlight.current = true;
    try {
      const snap = await api.getSnapshot(token, sessionId);
      setSnapshot(snap);
      setStreamingNodes((current) => reconcileStreamingNodes(current, snap));
    } finally {
      refreshInFlight.current = false;
    }
  };

  const attachNode = async (nodeId: string, meta?: Partial<StreamingNodeState>) => {
    if (nodeUnsubs.current[nodeId]) return;

    setStreamingNodes((current) => ({
      ...current,
      [nodeId]: current[nodeId] ?? { nodeId, content: "", done: false, ...meta },
    }));

    const unsub = await realtimeManager.subscribe<NodeStreamEvent>(
      token,
      `node:${nodeId}:stream`,
      (data) => {
        setStreamingNodes((current) => {
          const active = current[nodeId] ?? {
            nodeId,
            content: "",
            done: false,
            ...meta,
          };

          if (data.type === "chunk") {
            setSnapshot((prev) => (prev ? appendNodeContent(prev, nodeId, data.content) : prev));
            return { ...current, [nodeId]: { ...active, content: active.content + data.content } };
          }

          if (data.type === "done") {
            setSnapshot((prev) => (prev ? updateNodeStatus(prev, nodeId, "COMPLETE") : prev));
            void refreshSnapshot().catch((e: Error) => setError(e.message));
            return { ...current, [nodeId]: { ...active, done: true } };
          }

          return current;
        });
      },
    );

    nodeUnsubs.current[nodeId] = () => {
      unsub();
      delete nodeUnsubs.current[nodeId];
    };
  };

  useEffect(() => {
    hasScrolledToBottom.current = false;
    loadAll().catch((e: Error) => setError(e.message));
  }, [environmentId, sessionId, token]);

  useEffect(() => {
    if (!snapshot) return;
    void Promise.all(
      collectStreamingNodes(snapshot.rootNodes).map((node) =>
        attachNode(node.id, {
          question: node.question ?? undefined,
          parentNodeId: node.parentId ?? undefined,
          paragraphId: node.paragraphId ?? undefined,
          blockIndex: undefined,
        }),
      ),
    ).catch((e: Error) => setError(e.message));

    if (!hasScrolledToBottom.current && snapshot.rootNodes.length > 0) {
      hasScrolledToBottom.current = true;
      requestAnimationFrame(() => {
        chatBottomRef.current?.scrollIntoView({ behavior: "instant" });
      });
    }
  }, [snapshot]);

  useEffect(() => {
    if (!sessionId) return;

    const runRefresh = () => {
      void refreshSnapshot().catch((e: Error) => setError(e.message));
    };

    const interval = window.setInterval(
      runRefresh,
      document.visibilityState === "visible" ? 1200 : 2500,
    );

    const onVisibilityChange = () => {
      if (document.visibilityState === "visible") {
        runRefresh();
      }
    };

    document.addEventListener("visibilitychange", onVisibilityChange);

    return () => {
      window.clearInterval(interval);
      document.removeEventListener("visibilitychange", onVisibilityChange);
    };
  }, [sessionId, token]);

  useEffect(() => {
    if (!sessionId) return;
    let active = true;
    sessionUnsub.current?.();

    realtimeManager
      .subscribe<SessionEventChildNodeCreated | SessionEventRootNodeCreated>(
        token,
        `session:${sessionId}:events`,
        (data) => {
          if (!active) return;
          void attachNode(data.nodeId, {
            question: data.question,
            parentNodeId: data.type === "child_node_created" ? data.parentNodeId : undefined,
            paragraphId: data.type === "child_node_created" ? data.paragraphId : undefined,
            blockIndex: data.type === "child_node_created" ? data.blockIndex : undefined,
          });
          setSnapshot((prev) => {
            if (!prev) return prev;
            if (data.type === "root_node_created") {
              return insertRootNode(prev, createStreamingNode(data.nodeId, data.question, data.askedBy, data.depth));
            }
            return insertChildNode(
              prev,
              data.parentNodeId,
              data.blockIndex,
              data.paragraphId,
              createStreamingNode(
                data.nodeId,
                data.question,
                data.askedBy,
                data.depth,
                data.parentNodeId,
                data.paragraphId,
                data.blockIndex,
              ),
            );
          });
        },
      )
      .then((unsub) => {
        if (active) sessionUnsub.current = unsub;
        else unsub();
      })
      .catch((e: Error) => setError(e.message));

    return () => {
      active = false;
      sessionUnsub.current?.();
      sessionUnsub.current = null;
    };
  }, [sessionId, token]);

  useEffect(() => {
    if (!environmentId) return;
    let active = true;
    envUnsub.current?.();

    realtimeManager
      .subscribe<EnvironmentEventMemberUpdated>(token, `env:${environmentId}`, () => {
        if (!active) return;
        void Promise.all([
          api.listEnvironments(token),
          api.getMembers(token, environmentId),
        ])
          .then(([envs, mems]) => {
            setEnvironment(envs.find((env) => env.id === environmentId) ?? null);
            setMembers(mems);
          })
          .catch((e: Error) => setError(e.message));
      })
      .then((unsub) => {
        if (active) envUnsub.current = unsub;
        else unsub();
      })
      .catch((e: Error) => setError(e.message));

    return () => {
      active = false;
      envUnsub.current?.();
      envUnsub.current = null;
    };
  }, [environmentId, token]);

  useEffect(() => {
    return () => {
      sessionUnsub.current?.();
      envUnsub.current?.();
      Object.values(nodeUnsubs.current).forEach((fn) => fn());
    };
  }, []);

  useEffect(() => {
    autoSizeTextarea(composerTextareaRef.current, 48, 120);
  }, [rootQuestion]);

  useEffect(() => {
    const viewKey = getViewKey(currentView);
    const restoreScrollTop = viewScrollPositions.current[viewKey] ?? 0;

    requestAnimationFrame(() => {
      setViewportScrollTop(chatContainerRef.current, restoreScrollTop);
    });
  }, [currentView]);

  const withBusy = async (key: string, action: () => Promise<void>) => {
    setBusyKey(key);
    setError(null);
    try {
      await action();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Action failed");
    } finally {
      setBusyKey(null);
    }
  };

  const pushView = (view: ViewState) => {
    const currentKey = getViewKey(currentView);
    viewScrollPositions.current[currentKey] = getViewportScrollTop(chatContainerRef.current);
    setViewStack((prev) => [...prev, view]);
  };

  const popToView = (index: number) => {
    const currentKey = getViewKey(currentView);
    viewScrollPositions.current[currentKey] = getViewportScrollTop(chatContainerRef.current);
    setViewStack((prev) => prev.slice(0, index + 1));
  };

  return (
    <div className="session-page">
      <div className="session-header">
        <div>
          <h1>{selectedSession?.title ?? "Session"}</h1>
          {environment && (
            <span style={{ color: "var(--text-secondary)", fontSize: "0.9rem" }}>
              {environment.name}
            </span>
          )}
        </div>
        <div className="session-header-meta">
          <button
            className="btn btn-secondary btn-sm"
            onClick={() => navigate(`/app/environments/${environmentId}`)}
          >
            Back to environment
          </button>
        </div>
      </div>

      {error && <div className="error-banner">{error}</div>}

      {viewStack.length > 1 && (
        <div className="breadcrumb-nav">
          {viewStack.map((view, index) => {
            const isLast = index === viewStack.length - 1;
            let label = "";
            if (view.type === "root") label = "Root Chat";
            else if (view.type === "paragraph") label = "Paragraph View";
            else label = `Sub-topic: ${view.question.substring(0, 20)}${view.question.length > 20 ? "..." : ""}`;

            return (
              <span key={index} className="breadcrumb-item">
                <button
                  className={`breadcrumb-link ${isLast ? "active" : ""}`}
                  disabled={isLast}
                  onClick={() => !isLast && popToView(index)}
                >
                  {label}
                </button>
                {!isLast && <span className="breadcrumb-separator">/</span>}
              </span>
            );
          })}
        </div>
      )}

      <div className="chat-container" ref={chatContainerRef}>
        {currentView.type === "root" && (
          <>
            {snapshot?.rootNodes.length === 0 && Object.keys(streamingNodes).length === 0 && (
              <div className="empty-state">
                <h4>Start the conversation</h4>
                <p>Ask the shared AI something the room should explore together.</p>
              </div>
            )}
            {snapshot?.rootNodes.map((node) => (
              <ChatNode
                key={node.id}
                node={node}
                depth={0}
                streamingNodes={streamingNodes}
                onPushView={pushView}
              />
            ))}
            {Object.values(streamingNodes)
              .filter((node) => !node.done && !node.parentNodeId && !snapshot?.rootNodes.some((root) => findNodeInTree(root, node.nodeId)))
              .map((node) => <StreamingChatMessage key={node.nodeId} node={node} />)}
          </>
        )}

        {currentView.type === "paragraph" && (
          <div className="paragraph-view">
            {(() => {
              const context = snapshot
                ? findParagraphContext(snapshot.rootNodes, currentView.nodeId, currentView.blockIndex)
                : null;
              if (!context) {
                return (
                  <div className="empty-state">
                    <p>Waiting for this part of the response to arrive...</p>
                  </div>
                );
              }
              const childNodes = context.paragraph.id
                ? context.parentNode.children.filter((child) => child.paragraphId === context.paragraph.id)
                : [];
              return (
                <>
                  <div className="paragraph-context">
                    {renderBlock(parseSingleBlock(context.paragraph.content))}
                  </div>
                  <h4 className="paragraph-questions-title">Discussions on this paragraph</h4>
                  {childNodes.length === 0 ? (
                    <div className="empty-state">
                      <p>No discussions yet. Be the first to ask about this paragraph.</p>
                    </div>
                  ) : (
                    <div className="paragraph-questions-list">
                      {childNodes.map((child) => (
                        <div
                          key={child.id}
                          className={`child-question-item ${child.status === "STREAMING" ? "streaming" : ""}`}
                          onClick={() => pushView({ type: "node", nodeId: child.id, question: child.question })}
                        >
                          <div className="asker">@{child.askedByUsername ?? "unknown"}</div>
                          <div className="question-text">{child.question}</div>
                          <div className="view-thread-hint">
                            {child.status === "STREAMING" ? "Streaming..." : "View Thread ->"}
                          </div>
                        </div>
                      ))}
                    </div>
                  )}
                </>
              );
            })()}
          </div>
        )}

        {currentView.type === "node" && (
          <div className="node-view">
            {(() => {
              const nodeInSnapshot = snapshot?.rootNodes
                .map((root) => findNode(root, currentView.nodeId))
                .find(Boolean) as ConversationNodeDto | undefined;
              const liveNode = streamingNodes[currentView.nodeId];
              if (nodeInSnapshot) {
                return (
                  <ChatNode
                    node={nodeInSnapshot}
                    depth={nodeInSnapshot.depth}
                    streamingNodes={streamingNodes}
                    onPushView={pushView}
                  />
                );
              }
              if (liveNode) return <StreamingChatMessage node={liveNode} />;
              return (
                <div className="empty-state">
                  <p>Loading response...</p>
                </div>
              );
            })()}
          </div>
        )}

        <div ref={chatBottomRef} />
      </div>

      {(currentView.type === "root" || currentView.type === "paragraph") && (
        <div className="chat-composer">
          <form
            className="composer-box"
            onSubmit={(e) => {
              e.preventDefault();
              if (currentView.type === "root") {
                if (!rootQuestion.trim() || !sessionId || !canAskRoot) return;
                void withBusy("ask-root", async () => {
                  const question = rootQuestion;
                  setRootQuestion("");
                  const res = await api.askRoot(token, sessionId, question);
                  setSnapshot((prev) =>
                    prev
                      ? insertRootNode(prev, createStreamingNode(res.nodeId, question, me.username, 0))
                      : prev,
                  );
                  await attachNode(res.nodeId, { question });
                  window.setTimeout(() => {
                    void refreshSnapshot().catch((e: Error) => setError(e.message));
                  }, 500);
                });
                return;
              }

              if (!rootQuestion.trim() || !canAskParagraph) return;
              void withBusy(`ask-${currentView.blockIndex}`, async () => {
                const question = rootQuestion;
                setRootQuestion("");
                const res = await api.askOnBlock(
                  token,
                  currentView.nodeId,
                  currentView.blockIndex,
                  question,
                );
                setSnapshot((prev) =>
                  prev
                    ? insertChildNode(
                        prev,
                        currentView.nodeId,
                        res.blockIndex,
                        res.paragraphId,
                        createStreamingNode(
                          res.nodeId,
                          question,
                          me.username,
                          getNodeDepth(prev.rootNodes, currentView.nodeId) + 1,
                          currentView.nodeId,
                          res.paragraphId,
                          res.blockIndex,
                        ),
                      )
                    : prev,
                );
                await attachNode(res.nodeId, {
                  parentNodeId: currentView.nodeId,
                  paragraphId: res.paragraphId,
                  blockIndex: res.blockIndex,
                  question,
                });
                window.setTimeout(() => {
                  void refreshSnapshot().catch((e: Error) => setError(e.message));
                }, 500);
                pushView({ type: "node", nodeId: res.nodeId, question });
              });
            }}
          >
            <textarea
              ref={composerTextareaRef}
              value={rootQuestion}
              onChange={(e) => setRootQuestion(e.target.value)}
              placeholder={
                currentView.type === "root"
                  ? canAskRoot
                    ? "Ask the shared AI something..."
                    : "Only hosts can ask root questions"
                  : canAskParagraph
                    ? "Ask about this paragraph..."
                    : "Cannot ask here"
              }
              disabled={
                currentView.type === "root"
                  ? !canAskRoot || busyKey === "ask-root"
                  : !canAskParagraph || busyKey === `ask-${currentView.blockIndex}`
              }
              rows={1}
              onKeyDown={(e) => {
                if (e.key === "Enter" && !e.shiftKey) {
                  e.preventDefault();
                  e.currentTarget.form?.requestSubmit();
                }
              }}
            />
            <button
              className="btn btn-primary"
              disabled={
                currentView.type === "root"
                  ? !canAskRoot || busyKey === "ask-root"
                  : !canAskParagraph || busyKey === `ask-${currentView.blockIndex}`
              }
              type="submit"
            >
              ^
            </button>
          </form>
          {currentView.type === "root" && !canAskRoot && (
            <p className="composer-hint">
              Only hosts and co-hosts can ask root questions.
            </p>
          )}
        </div>
      )}
    </div>
  );
}

function findNode(root: ConversationNodeDto, nodeId: string): ConversationNodeDto | null {
  if (root.id === nodeId) return root;
  for (const child of root.children) {
    const found = findNode(child, nodeId);
    if (found) return found;
  }
  return null;
}

function findNodeInTree(root: ConversationNodeDto, nodeId: string): boolean {
  return findNode(root, nodeId) !== null;
}

function findParagraphContext(
  roots: ConversationNodeDto[],
  nodeId: string,
  blockIndex: number,
): { parentNode: ConversationNodeDto; paragraph: ParagraphDto } | null {
  const parentNode = roots.map((root) => findNode(root, nodeId)).find(Boolean) ?? null;
  if (!parentNode) return null;
  const paragraph = parentNode.paragraphs.find((item) => item.index === blockIndex) ?? null;
  if (!paragraph) return null;
  return { parentNode, paragraph };
}

function collectStreamingNodes(nodes: ConversationNodeDto[]): ConversationNodeDto[] {
  const result: ConversationNodeDto[] = [];
  for (const node of nodes) {
    if (node.status === "STREAMING") result.push(node);
    result.push(...collectStreamingNodes(node.children));
  }
  return result;
}

function flattenNodes(nodes: ConversationNodeDto[]): ConversationNodeDto[] {
  const result: ConversationNodeDto[] = [];
  for (const node of nodes) {
    result.push(node);
    result.push(...flattenNodes(node.children));
  }
  return result;
}

function hasStreamingNode(nodes: ConversationNodeDto[]): boolean {
  return flattenNodes(nodes).some((node) => node.status === "STREAMING");
}

function createStreamingNode(
  nodeId: string,
  question: string,
  askedByUsername: string,
  depth: number,
  parentNodeId?: string,
  paragraphId?: string,
  blockIndex?: number,
): ConversationNodeDto {
  return {
    id: nodeId,
    parentId: parentNodeId ?? null,
    paragraphId: paragraphId ?? null,
    depth,
    question,
    askedByUsername,
    fullContent: "",
    status: "STREAMING",
    createdAt: new Date().toISOString(),
    paragraphs: [],
    children: [],
  };
}

function insertRootNode(snapshot: SessionSnapshotDto, node: ConversationNodeDto): SessionSnapshotDto {
  if (snapshot.rootNodes.some((root) => root.id === node.id)) return snapshot;
  return { ...snapshot, rootNodes: [...snapshot.rootNodes, node] };
}

function insertChildNode(
  snapshot: SessionSnapshotDto,
  parentNodeId: string,
  blockIndex: number,
  paragraphId: string,
  childNode: ConversationNodeDto,
): SessionSnapshotDto {
  let inserted = false;
  const rootNodes = snapshot.rootNodes.map((root) =>
    updateNode(root, parentNodeId, (node) => {
      if (node.children.some((child) => child.id === childNode.id)) return node;
      inserted = true;
      return {
        ...node,
        paragraphs: node.paragraphs.map((paragraph) =>
          paragraph.index === blockIndex
            ? {
                ...paragraph,
                id: paragraph.id ?? paragraphId,
                childNodeCount: paragraph.childNodeCount + 1,
              }
            : paragraph,
        ),
        children: [...node.children, childNode],
      };
    }),
  );
  return inserted ? { ...snapshot, rootNodes } : snapshot;
}

function appendNodeContent(snapshot: SessionSnapshotDto, nodeId: string, chunk: string): SessionSnapshotDto {
  return {
    ...snapshot,
    rootNodes: snapshot.rootNodes.map((root) =>
      updateNode(root, nodeId, (node) => ({ ...node, fullContent: node.fullContent + chunk })),
    ),
  };
}

function updateNodeStatus(snapshot: SessionSnapshotDto, nodeId: string, status: string): SessionSnapshotDto {
  return {
    ...snapshot,
    rootNodes: snapshot.rootNodes.map((root) =>
      updateNode(root, nodeId, (node) => ({ ...node, status })),
    ),
  };
}

function updateNode(
  node: ConversationNodeDto,
  nodeId: string,
  updater: (node: ConversationNodeDto) => ConversationNodeDto,
): ConversationNodeDto {
  if (node.id === nodeId) return updater(node);

  let changed = false;
  const children = node.children.map((child) => {
    const updated = updateNode(child, nodeId, updater);
    if (updated !== child) changed = true;
    return updated;
  });
  return changed ? { ...node, children } : node;
}

function getNodeDepth(roots: ConversationNodeDto[], nodeId: string): number {
  return (roots.map((root) => findNode(root, nodeId)).find(Boolean) ?? null)?.depth ?? 0;
}

function reconcileStreamingNodes(
  current: Record<string, StreamingNodeState>,
  snapshot: SessionSnapshotDto,
): Record<string, StreamingNodeState> {
  const next = { ...current };
  const snapshotNodes = flattenNodes(snapshot.rootNodes);

  for (const node of snapshotNodes) {
    const live = next[node.id];
    if (!live && node.status !== "STREAMING") continue;

    next[node.id] = {
      nodeId: node.id,
      question: live?.question ?? node.question ?? undefined,
      parentNodeId: live?.parentNodeId ?? node.parentId ?? undefined,
      paragraphId: live?.paragraphId ?? node.paragraphId ?? undefined,
      blockIndex: live?.blockIndex,
      content: node.fullContent || live?.content || "",
      done: node.status === "COMPLETE" || live?.done === true,
    };

    if (node.status === "COMPLETE" && (node.fullContent || node.paragraphs.length > 0)) {
      delete next[node.id];
    }
  }

  return next;
}

function StreamingChatMessage({ node }: { node: StreamingNodeState }) {
  const bottomRef = useRef<HTMLDivElement>(null);
  const blocks = parseRenderableBlocks(node.content);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth", block: "nearest" });
  }, [node.content]);

  return (
    <>
      {node.question && (
        <div className="chat-message user-turn">
          <div className="chat-message-header">
            <div className="chat-avatar user">?</div>
            <span className="chat-sender">You</span>
          </div>
          <div className="chat-question">{node.question}</div>
        </div>
      )}
      <div className="chat-message assistant-turn is-streaming">
        <div className="chat-message-header">
          <div className="chat-avatar ai">AI</div>
          <span className="chat-sender">SangamAI</span>
          <span className="streaming-badge">Streaming</span>
        </div>
        <div className="ai-response">
          {blocks.length > 0 ? (
            blocks.map((block, index) => (
              <div key={`${block.type}-${index}`} className={`ai-paragraph ${block.type === "code" ? "ai-paragraph-code" : ""}`}>
                {renderBlock(block, { showCursor: index === blocks.length - 1 })}
              </div>
            ))
          ) : (
            <div className="ai-paragraph">
              <p className="streaming-placeholder">Thinking...</p>
            </div>
          )}
        </div>
        <div ref={bottomRef} />
      </div>
    </>
  );
}

function ChatNode({
  node,
  depth,
  streamingNodes,
  onPushView,
}: {
  node: ConversationNodeDto;
  depth: number;
  streamingNodes: Record<string, StreamingNodeState>;
  onPushView: (view: ViewState) => void;
}) {
  const liveNode = streamingNodes[node.id];
  const isStillStreaming = (liveNode && !liveNode.done) || node.status === "STREAMING";
  const content = liveNode?.content || node.fullContent || "";
  const hasFinalParagraphs = node.paragraphs.length > 0 && !isStillStreaming;
  const streamingBlocks = parseRenderableBlocks(content);
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (isStillStreaming) {
      bottomRef.current?.scrollIntoView({ behavior: "smooth", block: "nearest" });
    }
  }, [isStillStreaming, liveNode?.content]);

  return (
    <>
      {node.question && (
        <div className="chat-message user-turn">
          <div className="chat-message-header">
            <div className="chat-avatar user">
              {(node.askedByUsername ?? "?").charAt(0).toUpperCase()}
            </div>
            <span className="chat-sender">
              {node.askedByUsername ? `@${node.askedByUsername}` : "Room"}
            </span>
          </div>
          <div className="chat-question">{node.question}</div>
        </div>
      )}
      {(content || isStillStreaming) && (
        <div className={`chat-message assistant-turn ${isStillStreaming ? "is-streaming" : ""}`}>
          <div className="chat-message-header">
            <div className="chat-avatar ai">AI</div>
            <span className="chat-sender">SangamAI</span>
            {isStillStreaming && <span className="streaming-badge">Streaming</span>}
          </div>
          <div className="ai-response">
            {hasFinalParagraphs
              ? node.paragraphs.map((paragraph) => (
                  <div
                    key={paragraph.id ?? `block-${paragraph.index}`}
                    className={`ai-paragraph ${isCodeFenceBlock(paragraph.content) ? "ai-paragraph-code" : ""}`}
                    onClick={() => {
                      if (depth < MAX_THREAD_DEPTH) {
                        onPushView({ type: "paragraph", nodeId: node.id, blockIndex: paragraph.index });
                      }
                    }}
                  >
                    {renderBlock(parseSingleBlock(paragraph.content))}
                    {depth < MAX_THREAD_DEPTH && (
                      <div className="thread-indicator">
                        {paragraph.childNodeCount > 0 ? `${paragraph.childNodeCount} discussions` : "Discuss"}
                      </div>
                    )}
                  </div>
                ))
              : (streamingBlocks.length > 0 ? streamingBlocks : [{ type: "text", content: "Thinking..." } as RenderableBlock]).map((block, index, arr) => (
                  <div key={`${block.type}-${index}`} className={`ai-paragraph ${block.type === "code" ? "ai-paragraph-code" : ""}`}>
                    {renderBlock(block, { showCursor: isStillStreaming && index === arr.length - 1 })}
                  </div>
                ))}
          </div>
          <div ref={bottomRef} />
        </div>
      )}
    </>
  );
}

function renderBlock(block: RenderableBlock, options: { showCursor?: boolean } = {}) {
  const markdown =
    block.type === "code"
      ? `\`\`\`${block.language ?? ""}\n${block.content}${options.showCursor ? "\n|" : ""}\n\`\`\``
      : `${block.content}${options.showCursor ? "\n\n|" : ""}`;
  return <MarkdownBlock content={markdown} />;
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

function parseRenderableBlocks(content: string): RenderableBlock[] {
  const normalized = content.replace(/\r\n/g, "\n");
  const blocks: RenderableBlock[] = [];
  const codeFenceRegex = /```([\w+-]+)?\n([\s\S]*?)```/g;
  let lastIndex = 0;
  let match: RegExpExecArray | null;

  while ((match = codeFenceRegex.exec(normalized)) !== null) {
    const preceding = normalized.slice(lastIndex, match.index);
    blocks.push(...parseTextBlocks(preceding));
    blocks.push({
      type: "code",
      language: match[1] ?? null,
      content: match[2].replace(/\n$/, ""),
    });
    lastIndex = match.index + match[0].length;
  }

  blocks.push(...parseTextBlocks(normalized.slice(lastIndex)));
  return blocks;
}

function parseTextBlocks(content: string): RenderableBlock[] {
  return content
    .split(/\n{2,}/)
    .map((block) => block.trim())
    .filter(Boolean)
    .map((block) => ({ type: "text", content: block }));
}

function parseSingleBlock(content: string): RenderableBlock {
  const normalized = content.trim().replace(/\r\n/g, "\n");
  const codeMatch = normalized.match(/^```([\w+-]+)?\n([\s\S]*?)```$/);
  if (codeMatch) {
    return {
      type: "code",
      language: codeMatch[1] ?? null,
      content: codeMatch[2].replace(/\n$/, ""),
    };
  }
  return { type: "text", content: normalized };
}

function isCodeFenceBlock(content: string): boolean {
  return /^```[\w+-]*\n[\s\S]*```$/.test(content.trim());
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

function getViewKey(view: ViewState): string {
  switch (view.type) {
    case "root":
      return "root";
    case "paragraph":
      return `paragraph:${view.nodeId}:${view.blockIndex}`;
    case "node":
      return `node:${view.nodeId}`;
  }
}

function getViewportScrollTop(container: HTMLDivElement | null): number {
  if (typeof window !== "undefined") {
    return window.scrollY || window.pageYOffset || 0;
  }
  return container?.scrollTop ?? 0;
}

function setViewportScrollTop(container: HTMLDivElement | null, top: number) {
  if (typeof window !== "undefined") {
    window.scrollTo({ top, behavior: "auto" });
    return;
  }
  if (container) {
    container.scrollTop = top;
  }
}
