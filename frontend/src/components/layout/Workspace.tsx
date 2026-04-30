import type { ReactNode } from "react";
import { Sparkles, Brain } from "lucide-react";
import "./Workspace.css";

export default function Workspace({
  title,
  sessionId,
  facts,
  children,
}: {
  title: string;
  sessionId: string | null;
  facts: string[];
  children: ReactNode;
}) {
  return (
    <>
      <header className="wk-header">
        <div className="wk-header-left">
          <Sparkles size={18} className="wk-header-icon" />
          <h1 className="wk-header-title">{title}</h1>
        </div>
        <div className="wk-header-right">
          {facts.length > 0 && (
            <span className="wk-header-facts" title={`${facts.length} 条记忆：\n${facts.join("\n")}`}>
              <Brain size={13} />
              {facts.length}
            </span>
          )}
          <span className="wk-header-id">{sessionId ? `#${sessionId.slice(0, 8)}` : ""}</span>
        </div>
      </header>

      <div className="wk-body">{children}</div>

      <div className="wk-composer-dock" />
    </>
  );
}
