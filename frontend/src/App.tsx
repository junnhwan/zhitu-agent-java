import { useReducer, useState, useCallback, useEffect, useRef } from "react";
import MeshGradient, { MeshGradientStyles } from "./components/background/MeshGradient";
import NoiseOverlay, { NoiseOverlayStyles } from "./components/background/NoiseOverlay";
import AppShell from "./components/layout/AppShell";
import Sidebar from "./components/layout/Sidebar";
import Workspace from "./components/layout/Workspace";
import TracePanel from "./components/layout/TracePanel";
import ChatPanel from "./components/chat/ChatPanel";
import Composer from "./components/composer/Composer";
import KnowledgeModal from "./components/knowledge/KnowledgeModal";
import SettingsModal from "./components/knowledge/SettingsModal";
import HitlConfirmPanel from "./components/hitl/HitlConfirmPanel";
import { appReducer, useSessionManager } from "./hooks/useSessionManager";
import { useStreamingChat, emptyTraceDisplay, type TraceDisplay } from "./hooks/useStreamingChat";
import type { PendingToolCall } from "./types/events";
import { approveToolCall, denyToolCall } from "./api/tools";

export default function App() {
  const [state, dispatch] = useReducer(appReducer, {
    sessions: [] as import("./hooks/types").SessionState[],
    activeSessionId: null as string | null,
    sending: false,
  });

  const [trace, setTrace] = useState<TraceDisplay>(emptyTraceDisplay());
  const [knowledgeOpen, setKnowledgeOpen] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [pendingToolCall, setPendingToolCall] = useState<PendingToolCall | null>(null);

  const { handleNewSession, handleSelectSession, restoreLastSession, getActiveSession } =
    useSessionManager(state, dispatch);

  const { send, abort, resendWithApproval } = useStreamingChat(
    dispatch,
    setTrace,
    (pending) => setPendingToolCall(pending),
  );

  const handleSend = useCallback(
    (message: string) => {
      const s = getActiveSession();
      if (!s) return;
      send(s.sessionId, s.userId, message);
    },
    [getActiveSession, send],
  );

  const handleApprove = useCallback(
    async (pendingId: string) => {
      await approveToolCall(pendingId);
      setPendingToolCall(null);
      resendWithApproval(pendingId);
    },
    [resendWithApproval],
  );

  const handleDeny = useCallback(async (pendingId: string) => {
    await denyToolCall(pendingId);
    setPendingToolCall(null);
  }, []);

  const initialized = useRef(false);
  useEffect(() => {
    if (initialized.current) return;
    initialized.current = true;

    restoreLastSession().then((restored) => {
      if (!restored) {
        handleNewSession().catch(() => setTrace((t) => ({ ...t, status: "error" })));
      }
    });
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const activeSession = getActiveSession();
  const activeIdx = state.sessions.findIndex((s) => s.sessionId === state.activeSessionId);

  return (
    <>
      <MeshGradient />
      <MeshGradientStyles />
      <NoiseOverlay />
      <NoiseOverlayStyles />
      <AppShell
        sidebar={
          <Sidebar
            sessions={state.sessions}
            activeIdx={activeIdx}
            onNew={handleNewSession}
            onSelect={(i) => handleSelectSession(state.sessions[i].sessionId)}
            onOpenKnowledge={() => setKnowledgeOpen(true)}
            onOpenSettings={() => setSettingsOpen(true)}
          />
        }
        main={
          <Workspace
            title={activeSession?.title ?? "新对话"}
            sessionId={state.activeSessionId}
            facts={activeSession?.facts ?? []}
          >
            <ChatPanel
              messages={activeSession?.messages ?? []}
              onSuggestionClick={handleSend}
            />
          </Workspace>
        }
        aside={<TracePanel trace={trace} />}
        composer={<Composer sending={state.sending} onSend={handleSend} onAbort={abort} />}
      />
      <KnowledgeModal open={knowledgeOpen} onClose={() => setKnowledgeOpen(false)} />
      <SettingsModal open={settingsOpen} onClose={() => setSettingsOpen(false)} sessionId={state.activeSessionId} />
      <HitlConfirmPanel
        pending={pendingToolCall}
        onApprove={handleApprove}
        onDeny={handleDeny}
      />
    </>
  );
}
