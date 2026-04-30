import { motion } from "framer-motion";
import {
  Database, Wrench, Clock,
  Copy, Check, ChevronDown,
} from "lucide-react";
import { useState, useCallback } from "react";
import type { TraceDisplay } from "../../hooks/useStreamingChat";
import SpanTree from "./SpanTree";
import "./TracePanel.css";

export default function TracePanel({ trace }: { trace: TraceDisplay }) {
  const hasData = trace.status === "complete" || trace.status === "streaming";
  const [detailsOpen, setDetailsOpen] = useState(true);

  return (
    <div className="tp">
      <div className="tp-head">
        <span className="tp-title">Run Trace</span>
        <StatusDot status={trace.status} />
      </div>

      <div className="tp-pills">
        {trace.path && <Pill label={trace.path} variant="neutral" />}
        <Pill
          icon={<Database size={12} />}
          label="检索"
          variant={trace.retrievalHit ? "active" : "muted"}
        />
        <Pill
          icon={<Wrench size={12} />}
          label="工具"
          variant={trace.toolUsed ? "active" : "muted"}
        />
        {trace.latencyMs > 0 && (
          <Pill
            icon={<Clock size={12} />}
            label={`${trace.latencyMs}ms`}
            variant="neutral"
          />
        )}
      </div>

      <div className="tp-bar-track">
        <motion.div
          className="tp-bar-fill"
          initial={{ width: 0 }}
          animate={{
            width:
              trace.status === "streaming" ? "60%" :
              trace.status === "complete" ? "100%" : "0%",
          }}
          transition={{ duration: 0.8, ease: "easeOut" }}
        />
      </div>

      {hasData && (
        <motion.div
          className="tp-body"
          initial={{ opacity: 0, y: 8 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.3 }}
        >
          <button
            type="button"
            className={`tp-section-toggle ${detailsOpen ? "open" : ""}`}
            onClick={() => setDetailsOpen((v) => !v)}
          >
            <span>详情</span>
            <ChevronDown size={14} />
          </button>

          {detailsOpen && (
            <>
              <div className="tp-group">
                <div className="tp-group-label">检索</div>
                <div className="tp-rows">
                  <Row label="模式" value={trace.retrievalMode || "—"} />
                  <Row label="片段数" value={String(trace.snippetCount)} />
                  <Row label="候选数" value={String(trace.retrievalCandidateCount)} />
                  <Row label="Top 分数" value={trace.topScore > 0 ? trace.topScore.toFixed(3) : "—"} />
                  {trace.topSource && <Row label="来源" value={trace.topSource} />}
                  {trace.rerankModel && <Row label="Rerank" value={trace.rerankModel} />}
                  {trace.rerankTopScore > 0 && (
                    <Row label="Rerank 分数" value={trace.rerankTopScore.toFixed(3)} />
                  )}
                  <Row label="记忆条数" value={String(trace.factCount)} />
                </div>
              </div>

              <div className="tp-group">
                <div className="tp-group-label">性能</div>
                <div className="tp-rows">
                  <Row label="耗时" value={trace.latencyMs > 0 ? `${trace.latencyMs}ms` : "—"} />
                  <Row label="输入 token" value={trace.inputTokenEstimate > 0 ? String(trace.inputTokenEstimate) : "—"} />
                  <Row label="输出 token" value={trace.outputTokenEstimate > 0 ? String(trace.outputTokenEstimate) : "—"} />
                  <Row label="上下文策略" value={trace.contextStrategy || "—"} />
                </div>
              </div>

              {trace.requestId && (
                <div className="tp-request">
                  <span className="tp-request-label">Request</span>
                  <span className="tp-request-val">{trace.requestId}</span>
                </div>
              )}
            </>
          )}

          {trace.spans && trace.spans.length > 0 && (
            <SpanTree spans={trace.spans} />
          )}

          <CopyTraceButton trace={trace} />
        </motion.div>
      )}
    </div>
  );
}

function Pill({
  icon, label, variant,
}: {
  icon?: React.ReactNode; label: string; variant: "active" | "muted" | "neutral";
}) {
  return (
    <span className={`tp-pill tp-pill-${variant}`}>
      {icon}
      <span>{label}</span>
    </span>
  );
}

function StatusDot({ status }: { status: string }) {
  const cls =
    status === "streaming" ? "tp-status-streaming" :
    status === "complete" ? "tp-status-complete" :
    status === "error" ? "tp-status-error" : "tp-status-idle";
  return (
    <span className={`tp-status ${cls}`}>
      <span className="tp-status-dot" />
      <span>{statusLabel(status)}</span>
    </span>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="tp-row">
      <span className="tp-row-label">{label}</span>
      <span className="tp-row-val" title={value}>{value}</span>
    </div>
  );
}

function CopyTraceButton({ trace }: { trace: TraceDisplay }) {
  const [copied, setCopied] = useState(false);

  const handleCopy = useCallback(() => {
    const { status, ...data } = trace;
    navigator.clipboard.writeText(JSON.stringify(data, null, 2)).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    });
  }, [trace]);

  return (
    <button type="button" className="tp-copy-btn" onClick={handleCopy}>
      {copied ? <Check size={14} /> : <Copy size={14} />}
      {copied ? "已复制" : "复制 Trace"}
    </button>
  );
}

function statusLabel(s: string) {
  switch (s) {
    case "idle": return "待命";
    case "streaming": return "生成中";
    case "complete": return "完成";
    case "error": return "出错";
    default: return s;
  }
}
