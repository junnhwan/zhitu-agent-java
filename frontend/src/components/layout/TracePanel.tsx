import { motion } from "framer-motion";
import {
  Activity, Database, Wrench, CircleDot,
  Copy, Check,
} from "lucide-react";
import { useState, useCallback } from "react";
import type { TraceDisplay } from "../../hooks/useStreamingChat";
import "./TracePanel.css";

export default function TracePanel({ trace }: { trace: TraceDisplay }) {
  const hasData = trace.status === "complete" || trace.status === "streaming";

  return (
    <div>
      <div className="aside-title">Run Trace</div>

      <div className="aside-metrics">
        <Metric icon={<Activity size={15} />} label="路径" value={trace.path || "—"} />
        <Metric icon={<Database size={15} />} label="RAG 命中" value={trace.retrievalHit ? "是" : "否"} positive={trace.retrievalHit} />
        <Metric icon={<Wrench size={15} />} label="工具调用" value={trace.toolUsed ? "是" : "否"} positive={trace.toolUsed} />
        <Metric icon={<CircleDot size={15} />} label="状态" value={statusLabel(trace.status)} className={`status-${trace.status}`} />
      </div>

      <div className="aside-bar-track">
        <motion.div
          className="aside-bar-fill"
          initial={{ width: 0 }}
          animate={{ width: trace.status === "streaming" ? "60%" : trace.status === "complete" ? "100%" : "0%" }}
          transition={{ duration: 0.8, ease: "easeOut" }}
        />
      </div>

      {hasData && (
        <motion.div
          className="aside-extended"
          initial={{ opacity: 0, y: 8 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.3 }}
        >
          <div className="aside-section-title">检索详情</div>
          <div className="aside-detail-grid">
            <DetailItem label="检索模式" value={trace.retrievalMode || "—"} />
            <DetailItem label="片段数" value={String(trace.snippetCount)} />
            <DetailItem label="候选数" value={String(trace.retrievalCandidateCount)} />
            <DetailItem label="Top 分数" value={trace.topScore > 0 ? trace.topScore.toFixed(3) : "—"} />
            {trace.topSource && <DetailItem label="来源" value={trace.topSource} />}
            {trace.rerankModel && <DetailItem label="Rerank" value={trace.rerankModel} />}
            {trace.rerankTopScore > 0 && <DetailItem label="Rerank 分数" value={trace.rerankTopScore.toFixed(3)} />}
            <DetailItem label="记忆条数" value={String(trace.factCount)} />
          </div>

          <div className="aside-section-title">性能</div>
          <div className="aside-detail-grid">
            <DetailItem label="耗时" value={trace.latencyMs > 0 ? `${trace.latencyMs}ms` : "—"} />
            <DetailItem label="输入 token" value={trace.inputTokenEstimate > 0 ? String(trace.inputTokenEstimate) : "—"} />
            <DetailItem label="输出 token" value={trace.outputTokenEstimate > 0 ? String(trace.outputTokenEstimate) : "—"} />
            <DetailItem label="上下文策略" value={trace.contextStrategy || "—"} />
          </div>

          {trace.requestId && (
            <div className="aside-request-id">
              <span className="aside-request-label">Request</span>
              <span className="aside-request-val">{trace.requestId}</span>
            </div>
          )}

          <CopyTraceButton trace={trace} />
        </motion.div>
      )}
    </div>
  );
}

function Metric({ icon, label, value, positive, className }: {
  icon: React.ReactNode; label: string; value: string; positive?: boolean; className?: string;
}) {
  return (
    <div className="aside-metric">
      <div className="aside-metric-head">
        {icon}
        <span className="aside-metric-label">{label}</span>
      </div>
      <motion.span
        key={value}
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        className={`aside-metric-val ${positive ? "positive" : ""} ${className ?? ""}`}
      >
        {value}
      </motion.span>
    </div>
  );
}

function DetailItem({ label, value }: { label: string; value: string }) {
  return (
    <div className="aside-detail-item">
      <span className="aside-detail-label">{label}</span>
      <span className="aside-detail-val">{value}</span>
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
    <button type="button" className="aside-copy-btn" onClick={handleCopy}>
      {copied ? <Check size={14} /> : <Copy size={14} />}
      {copied ? "已复制" : "复制 Trace"}
    </button>
  );
}

function statusLabel(s: string) {
  switch (s) {
    case "idle": return "待命";
    case "streaming": return "生成中…";
    case "complete": return "完成";
    case "error": return "出错";
    default: return s;
  }
}
