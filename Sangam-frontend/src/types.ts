export interface ApiResponse<T> {
  success: boolean;
  data?: T;
  message?: string;
}

export interface AuthResponse {
  token: string;
  username: string;
  displayName: string;
  userId: string;
}

export interface CurrentUser {
  id: string;
  username: string;
  displayName: string;
  email: string;
}

export interface ProjectResponse {
  id: string;
  name: string;
  description: string | null;
  systemInstructions: string;
  knowledgeContext: string;
  createdAt: string;
  updatedAt: string;
}

export interface SoloChatMessageResponse {
  id: string;
  role: "USER" | "ASSISTANT" | "SYSTEM";
  status: "STREAMING" | "COMPLETE";
  content: string;
  createdAt: string;
}

export interface SoloChatSummaryResponse {
  id: string;
  title: string;
  pinned: boolean;
  projectId: string | null;
  projectName: string | null;
  lastMessagePreview: string;
  createdAt: string;
  updatedAt: string;
}

export interface SoloChatDetailResponse {
  id: string;
  title: string;
  pinned: boolean;
  project: ProjectResponse | null;
  createdAt: string;
  updatedAt: string;
  messages: SoloChatMessageResponse[];
}

export interface EnvironmentResponse {
  id: string;
  name: string;
  description: string;
  inviteCode: string;
  hostUsername: string;
  createdAt: string;
}

export interface MemberResponse {
  userId: string;
  username: string;
  displayName: string;
  role: "OWNER" | "CO_HOST" | "MEMBER";
  owner: boolean;
  canInteractWithAi: boolean;
}

export interface SessionListItem {
  sessionId: string;
  title: string;
  status: string;
  createdAt: string;
  createdBy: string;
}

export interface ParagraphDto {
  id: string | null;
  index: number;
  content: string;
  childNodeCount: number;
}

export interface ConversationNodeDto {
  id: string;
  parentId: string | null;
  paragraphId: string | null;
  depth: number;
  question: string;
  askedByUsername: string | null;
  fullContent: string;
  status: string;
  createdAt: string;
  paragraphs: ParagraphDto[];
  children: ConversationNodeDto[];
}

export interface SessionSnapshotDto {
  sessionId: string;
  title: string;
  status: string;
  rootNodes: ConversationNodeDto[];
}

export interface CentrifugoConnectionToken {
  token: string;
  wsUrl: string;
}

export interface StreamEventChunk {
  type: "chunk";
  content: string;
}

export interface StreamEventParagraphReady {
  type: "paragraph_ready";
  paragraphId: string;
  index: number;
  content: string;
}

export interface StreamEventDone {
  type: "done";
}

export interface SessionEventChildNodeCreated {
  type: "child_node_created";
  nodeId: string;
  parentNodeId: string;
  paragraphId: string;
  blockIndex: number;
  depth: number;
  question: string;
  askedBy: string;
}

export interface SessionEventRootNodeCreated {
  type: "root_node_created";
  nodeId: string;
  depth: number;
  question: string;
  askedBy: string;
}

export interface EnvironmentEventMemberUpdated {
  type: "member_added" | "member_role_updated" | "member_permission_updated";
  username: string;
  displayName: string;
  role: "OWNER" | "CO_HOST" | "MEMBER";
  owner: boolean;
  canInteractWithAi: boolean;
}

export type NodeStreamEvent =
  | StreamEventChunk
  | StreamEventParagraphReady
  | StreamEventDone;

export interface StreamingNodeState {
  nodeId: string;
  content: string;
  done: boolean;
  parentNodeId?: string;
  paragraphId?: string;
  blockIndex?: number;
  question?: string;
}
