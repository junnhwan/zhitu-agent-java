import { motion } from "framer-motion";
import { MessageSquarePlus, Settings, Layers } from "lucide-react";
import "./Sidebar.css";

export default function Sidebar({
  sessions,
  activeIdx,
  onNew,
  onSelect,
  onOpenKnowledge,
  onOpenSettings,
}: {
  sessions: { sessionId: string; title: string }[];
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
        {sessions.slice(0, 5).map((s, i) => (
          <motion.button
            key={s.sessionId}
            type="button"
            className={`nav-session-btn ${i === activeIdx ? "active" : ""}`}
            onClick={() => onSelect(i)}
            title={s.title}
            whileHover={{ scale: 1.08 }}
            whileTap={{ scale: 0.95 }}
          >
            <span className="nav-session-num">{i + 1}</span>
          </motion.button>
        ))}
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
        width: 40px; height: 40px; border-radius: 14px;
        background: var(--g-azure);
        display: flex; align-items: center; justify-content: center;
        box-shadow: 0 4px 16px rgba(56,189,248,0.25);
        position: relative;
        margin-bottom: 4px;
      }
      .nav-logo::before {
        content: ''; position: absolute; inset: 0; border-radius: 14px;
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
        width: 38px; height: 38px; border-radius: 12px;
        background: rgba(255,255,255,0.35);
        backdrop-filter: blur(8px);
        display: flex; align-items: center; justify-content: center;
        transition: all 0.25s ease;
        position: relative;
        font-family: var(--sans); color: var(--t3); font-weight: 700; font-size: 0.85rem;
      }
      .nav-session-btn:hover {
        background: rgba(255,255,255,0.55);
        color: var(--t2);
      }
      .nav-session-btn.active {
        background: var(--azure-glow);
        color: var(--azure-deep);
        box-shadow: 0 2px 8px rgba(56,189,248,0.15);
      }
      .nav-session-btn.active::after {
        content: ''; position: absolute; left: -16px; top: 50%; transform: translateY(-50%);
        width: 3px; height: 16px; border-radius: 0 3px 3px 0;
        background: var(--g-azure);
      }

      .nav-spacer { flex: 1; }

      .nav-icon-btn {
        appearance: none; border: none; cursor: pointer;
        width: 42px; height: 42px; border-radius: 14px;
        background: rgba(255,255,255,0.3);
        backdrop-filter: blur(8px);
        display: flex; align-items: center; justify-content: center;
        color: var(--t3);
        transition: all 0.25s ease;
      }
      .nav-icon-btn:hover { background: rgba(255,255,255,0.5); color: var(--t2); }
      .nav-icon-btn.ghost { background: transparent; }
      .nav-icon-btn.ghost:hover { background: rgba(255,255,255,0.25); }
      .nav-icon-btn.disabled { opacity: 0.3; cursor: not-allowed; pointer-events: none; }
    `}</style>
  );
}
