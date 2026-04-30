import { motion } from "framer-motion";
import { MessageSquarePlus, Settings, Layers } from "lucide-react";
import type { SessionState } from "../../hooks/types";
import "./Sidebar.css";

function hashHue(id: string): number {
  let h = 0;
  for (let i = 0; i < id.length; i++) {
    h = (h * 31 + id.charCodeAt(i)) >>> 0;
  }
  return h % 360;
}

function sessionToken(s: SessionState, idx: number): string {
  const firstUser = s.messages?.find((m) => m.role === "user")?.content;
  if (firstUser) {
    const first = firstUser.trim().slice(0, 1);
    if (first) return first;
  }
  return String(idx + 1);
}

export default function Sidebar({
  sessions,
  activeIdx,
  onNew,
  onSelect,
  onOpenKnowledge,
  onOpenSettings,
}: {
  sessions: SessionState[];
  activeIdx: number;
  onNew: () => void;
  onSelect: (i: number) => void;
  onOpenKnowledge: () => void;
  onOpenSettings: () => void;
}) {
  return (
    <>
      <div className="nav-logo">
        <div className="nav-logo-dot" />
      </div>

      <div className="nav-divider" />

      <div className="nav-sessions">
        {sessions.slice(0, 5).map((s, i) => {
          const hue = hashHue(s.sessionId);
          const token = sessionToken(s, i);
          const isActive = i === activeIdx;
          const style = isActive
            ? undefined
            : ({
                "--hue": String(hue),
              } as React.CSSProperties);
          return (
            <motion.button
              key={s.sessionId}
              type="button"
              className={`nav-session-btn ${isActive ? "active" : ""}`}
              style={style}
              onClick={() => onSelect(i)}
              title={s.title}
              whileHover={{ scale: 1.08 }}
              whileTap={{ scale: 0.95 }}
            >
              <span className="nav-session-token">{token}</span>
            </motion.button>
          );
        })}
      </div>

      <div className="nav-spacer" />
      <div className="nav-divider" />

      <motion.button
        type="button"
        className="nav-icon-btn"
        onClick={onNew}
        title="新建会话"
        whileHover={{ scale: 1.1 }}
        whileTap={{ scale: 0.92 }}
      >
        <MessageSquarePlus size={22} />
      </motion.button>

      <motion.button
        type="button"
        className="nav-icon-btn ghost"
        onClick={onOpenKnowledge}
        title="知识库"
        whileHover={{ scale: 1.1 }}
        whileTap={{ scale: 0.92 }}
      >
        <Layers size={20} />
      </motion.button>

      <motion.button
        type="button"
        className="nav-icon-btn ghost"
        onClick={onOpenSettings}
        title="运行配置"
        whileHover={{ scale: 1.1 }}
        whileTap={{ scale: 0.92 }}
      >
        <Settings size={20} />
      </motion.button>

      <SidebarStyles />
    </>
  );
}

function SidebarStyles() {
  return (
    <style>{`
      .nav-logo {
        width: 40px; height: 40px; border-radius: var(--r-sm);
        background: var(--g-azure);
        display: flex; align-items: center; justify-content: center;
        box-shadow: 0 4px 16px rgba(14,165,233,0.28);
        position: relative;
        margin-bottom: 4px;
      }
      .nav-logo::before {
        content: ''; position: absolute; inset: 0; border-radius: inherit;
        background: linear-gradient(135deg, rgba(255,255,255,0.35) 0%, transparent 50%);
      }
      .nav-logo-dot {
        width: 12px; height: 12px; border-radius: 50%;
        background: rgba(255,255,255,0.9);
        box-shadow: 0 0 6px rgba(255,255,255,0.4);
      }

      .nav-divider {
        width: 28px; height: 1px; border-radius: 1px;
        background: rgba(0,0,0,0.06); margin: 8px 0;
      }

      .nav-sessions {
        display: flex; flex-direction: column; align-items: center; gap: 6px;
      }

      .nav-session-btn {
        appearance: none; border: none; cursor: pointer;
        width: 38px; height: 38px; border-radius: var(--r-sm);
        background: hsl(var(--hue, 200), 38%, 92%);
        backdrop-filter: blur(8px);
        display: flex; align-items: center; justify-content: center;
        transition: background 0.2s ease, color 0.2s ease, box-shadow 0.2s ease;
        position: relative;
        font-family: var(--sans);
        color: hsl(var(--hue, 200), 30%, 35%);
        font-weight: 700; font-size: var(--fs-sm);
        overflow: hidden;
      }
      .nav-session-btn:hover {
        background: hsl(var(--hue, 200), 50%, 85%);
        color: hsl(var(--hue, 200), 40%, 25%);
      }
      .nav-session-btn.active {
        background: var(--g-azure-solid);
        color: #fff;
        box-shadow: 0 4px 14px rgba(14,165,233,0.32);
      }
      .nav-session-btn.active::after {
        content: ''; position: absolute; left: -16px; top: 50%; transform: translateY(-50%);
        width: 3px; height: 18px; border-radius: 0 3px 3px 0;
        background: var(--g-azure);
      }
      .nav-session-token {
        line-height: 1;
        max-width: 100%;
        overflow: hidden;
        white-space: nowrap;
      }

      .nav-spacer { flex: 1; }

      .nav-icon-btn {
        appearance: none; border: none; cursor: pointer;
        width: 42px; height: 42px; border-radius: var(--r-sm);
        background: rgba(255,255,255,0.3);
        backdrop-filter: blur(8px);
        display: flex; align-items: center; justify-content: center;
        color: var(--t3);
        transition: all 0.25s ease;
      }
      .nav-icon-btn:hover { background: rgba(255,255,255,0.55); color: var(--t2); }
      .nav-icon-btn.ghost { background: transparent; }
      .nav-icon-btn.ghost:hover { background: rgba(255,255,255,0.3); }
      .nav-icon-btn.disabled { opacity: 0.3; cursor: not-allowed; pointer-events: none; }
    `}</style>
  );
}
