import { useEffect, useState, type ReactNode } from "react";
import { NavLink, useLocation, useNavigate } from "react-router-dom";
import type { CurrentUser, ProjectResponse, SoloChatSummaryResponse } from "../types";
import { api } from "../lib/api";
import ChatOverflowMenu from "./ChatOverflowMenu";

export default function AppShell({
  token,
  me,
  onLogout,
  onWorkspaceChanged,
  recentChats,
  children,
}: {
  token: string;
  me: CurrentUser;
  onLogout: () => void;
  onWorkspaceChanged: () => Promise<void>;
  recentChats: SoloChatSummaryResponse[];
  children: ReactNode;
}) {
  const [collapsed, setCollapsed] = useState(false);
  const [mobileOpen, setMobileOpen] = useState(false);
  const location = useLocation();
  const navigate = useNavigate();
  const isMobile = typeof window !== "undefined" && window.innerWidth <= 1024;
  const showSidebar = mobileOpen || !collapsed;
  const [projects, setProjects] = useState<ProjectResponse[]>([]);

  const toggleSidebar = () => {
    if (isMobile) {
      setMobileOpen((prev) => !prev);
      return;
    }
    setCollapsed((prev) => !prev);
  };

  const closeMobile = () => setMobileOpen(false);

  useEffect(() => {
    closeMobile();
  }, [location.pathname]);

  useEffect(() => {
    api.listProjects(token).then(setProjects).catch(() => setProjects([]));
  }, [token, recentChats]);

  return (
    <div className="workspace-layout">
      <div
        className={`workspace-overlay ${mobileOpen ? "visible" : ""}`}
        onClick={closeMobile}
      />

      <button
        className={`workspace-sidebar-floating-toggle ${!showSidebar ? "visible" : ""}`}
        onClick={toggleSidebar}
        aria-label="Open sidebar"
        type="button"
      >
        <SparkIcon />
      </button>

      <aside className={`workspace-sidebar ${showSidebar ? "open" : "collapsed"}`}>
        <div className="workspace-sidebar-head">
          <button
            className="workspace-sidebar-toggle"
            onClick={toggleSidebar}
            type="button"
            aria-label={showSidebar ? "Collapse sidebar" : "Expand sidebar"}
          >
            <SparkIcon />
          </button>
          <div className="workspace-brand">
            <div className="logo-icon">S</div>
            <div>
              <strong>SangamAI</strong>
              <span>Personal workspace</span>
            </div>
          </div>
        </div>

        <div className="workspace-sidebar-scroll">
          <button
            className="workspace-primary-action"
            type="button"
            onClick={() => navigate("/app")}
          >
            <span className="workspace-icon-pill">+</span>
            New Chat
          </button>

          <nav className="workspace-nav">
            <NavLink
              to="/app"
              end
              className={({ isActive }) => `workspace-nav-link ${isActive ? "active" : ""}`}
            >
              <span className="workspace-nav-icon">◎</span>
              <span>Home</span>
            </NavLink>
            <NavLink
              to="/app/projects"
              className={({ isActive }) => `workspace-nav-link ${isActive ? "active" : ""}`}
            >
              <span className="workspace-nav-icon">◇</span>
              <span>Projects</span>
            </NavLink>
            <NavLink
              to="/app/history"
              className={({ isActive }) => `workspace-nav-link ${isActive ? "active" : ""}`}
            >
              <span className="workspace-nav-icon">⌕</span>
              <span>History</span>
            </NavLink>
            <NavLink
              to="/app/environments"
              className={({ isActive }) => `workspace-nav-link ${isActive ? "active" : ""}`}
            >
              <span className="workspace-nav-icon">◫</span>
              <span>Environments</span>
            </NavLink>
          </nav>

          <div className="workspace-recent-section">
            <div className="workspace-section-label">Recent</div>
            <div className="workspace-recent-list">
              {recentChats.length === 0 ? (
                <div className="workspace-recent-empty">
                  Personal chat history will appear here.
                </div>
              ) : (
                recentChats.map((chat) => (
                  <div key={chat.id} className="workspace-recent-row">
                    <button
                      className="workspace-recent-item"
                      type="button"
                      onClick={() => navigate(`/app/chats/${chat.id}`)}
                    >
                      <span className="workspace-recent-title">
                        {chat.pinned && <span className="workspace-recent-pin">📌</span>}
                        {chat.title}
                      </span>
                    </button>
                    <ChatOverflowMenu
                      token={token}
                      chat={chat}
                      projects={projects}
                      onChanged={onWorkspaceChanged}
                      onDeleted={(deletedId) => {
                        if (location.pathname.endsWith(deletedId)) {
                          navigate("/app/history");
                        }
                      }}
                    />
                  </div>
                ))
              )}
            </div>
          </div>
        </div>

        <div className="workspace-sidebar-foot">
          <NavLink
            to="/app/profile"
            className={({ isActive }) => `workspace-profile-chip ${isActive ? "active" : ""}`}
          >
            <div className="workspace-profile-avatar">
              {me.displayName.charAt(0).toUpperCase()}
            </div>
            <div className="workspace-profile-copy">
              <strong>{me.displayName}</strong>
              <span>{me.email}</span>
            </div>
          </NavLink>

          <button
            className="workspace-logout"
            onClick={() => {
              closeMobile();
              onLogout();
            }}
            type="button"
          >
            Logout
          </button>
        </div>
      </aside>

      <main className={`workspace-main ${showSidebar ? "" : "expanded"}`}>
        <div className="app-content">
          <div key={location.pathname} className="route-transition">
            {children}
          </div>
        </div>
      </main>
    </div>
  );
}

function SparkIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path
        d="M12 2.5 13.9 8l5.6 1.9-5.6 1.9L12 17.5l-1.9-5.7L4.5 9.9 10.1 8 12 2.5Z"
        stroke="currentColor"
        strokeWidth="1.4"
        strokeLinejoin="round"
      />
      <path
        d="M18.5 16.5 19.3 19l2.2.8-2.2.7-.8 2.5-.8-2.5-2.2-.7 2.2-.8.8-2.5Z"
        fill="currentColor"
      />
    </svg>
  );
}
