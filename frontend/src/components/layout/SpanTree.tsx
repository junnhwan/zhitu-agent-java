import { useMemo, useState } from "react";
import { motion } from "framer-motion";
import { ChevronRight, ChevronDown } from "lucide-react";
import type { Span } from "../../types/api";
import "./SpanTree.css";

interface SpanNodeData {
  span: Span;
  children: SpanNodeData[];
  depth: number;
  startOffsetMs: number;
  durationMs: number;
}

interface TreeStats {
  roots: SpanNodeData[];
  minStart: number;
  totalDuration: number;
  maxDepth: number;
}

export default function SpanTree({ spans }: { spans: Span[] }) {
  const stats = useMemo(() => buildTree(spans), [spans]);

  if (stats.roots.length === 0) {
    return null;
  }

  return (
    <div className="span-tree">
      <div className="span-tree-header">
        <span className="span-tree-title">Span Tree</span>
        <span className="span-tree-meta">
          {spans.length} spans · {Math.max(stats.totalDuration, 0)}ms
        </span>
      </div>
      <div className="span-tree-list">
        {stats.roots.map((node) => (
          <SpanNode
            key={node.span.spanId}
            node={node}
            totalDuration={stats.totalDuration}
            initiallyExpanded
          />
        ))}
      </div>
    </div>
  );
}

function SpanNode({
  node,
  totalDuration,
  initiallyExpanded,
}: {
  node: SpanNodeData;
  totalDuration: number;
  initiallyExpanded?: boolean;
}) {
  const [expanded, setExpanded] = useState(initiallyExpanded ?? false);
  const [showAttrs, setShowAttrs] = useState(false);
  const hasChildren = node.children.length > 0;
  const safeTotal = totalDuration > 0 ? totalDuration : 1;
  const widthPercent = clampPercent((node.durationMs / safeTotal) * 100);
  const offsetPercent = clampPercent((node.startOffsetMs / safeTotal) * 100);
  const attrEntries = Object.entries(node.span.attributes ?? {});

  return (
    <div className="span-node" style={{ paddingLeft: `${node.depth * 14}px` }}>
      <div
        className={`span-row span-status-${node.span.status}`}
        onClick={() => hasChildren && setExpanded((v) => !v)}
        role={hasChildren ? "button" : undefined}
      >
        <span className="span-toggle">
          {hasChildren ? (
            expanded ? <ChevronDown size={12} /> : <ChevronRight size={12} />
          ) : (
            <span className="span-bullet" />
          )}
        </span>
        <span className="span-name">{node.span.name}</span>
        <span className="span-kind">{node.span.kind}</span>
        <span className="span-duration">{Math.max(node.durationMs, 0)}ms</span>
        <span className={`span-status-badge span-status-${node.span.status}`}>
          {node.span.status}
        </span>
        <div className="span-bar-track">
          <motion.div
            className={`span-bar-fill span-status-${node.span.status}`}
            initial={{ width: 0 }}
            animate={{ width: `${Math.max(widthPercent, 1)}%` }}
            transition={{ duration: 0.4 }}
            style={{ marginLeft: `${offsetPercent}%` }}
          />
        </div>
      </div>
      {attrEntries.length > 0 && (
        <button
          type="button"
          className="span-attrs-toggle"
          onClick={(e) => {
            e.stopPropagation();
            setShowAttrs((v) => !v);
          }}
        >
          {showAttrs ? "隐藏属性" : `查看 ${attrEntries.length} 项属性`}
        </button>
      )}
      {showAttrs && attrEntries.length > 0 && (
        <div className="span-attrs">
          {attrEntries.map(([k, v]) => (
            <div className="span-attr-row" key={k}>
              <span className="span-attr-key">{k}</span>
              <span className="span-attr-val">{formatAttrValue(v)}</span>
            </div>
          ))}
        </div>
      )}
      {expanded && hasChildren && (
        <div className="span-children">
          {node.children.map((child) => (
            <SpanNode
              key={child.span.spanId}
              node={child}
              totalDuration={totalDuration}
            />
          ))}
        </div>
      )}
    </div>
  );
}

function buildTree(spans: Span[]): TreeStats {
  if (!spans || spans.length === 0) {
    return { roots: [], minStart: 0, totalDuration: 0, maxDepth: 0 };
  }
  const minStart = Math.min(...spans.map((s) => s.startEpochMillis));
  const maxEnd = Math.max(...spans.map((s) => s.endEpochMillis));
  const totalDuration = Math.max(maxEnd - minStart, 0);

  const byId = new Map<string, SpanNodeData>();
  spans.forEach((span) => {
    byId.set(span.spanId, {
      span,
      children: [],
      depth: 0,
      startOffsetMs: Math.max(span.startEpochMillis - minStart, 0),
      durationMs: Math.max(span.endEpochMillis - span.startEpochMillis, 0),
    });
  });

  const roots: SpanNodeData[] = [];
  byId.forEach((node) => {
    const parentId = node.span.parentSpanId;
    if (parentId && byId.has(parentId)) {
      byId.get(parentId)!.children.push(node);
    } else {
      roots.push(node);
    }
  });

  // Sort each level by start time so the tree reads top-down chronologically.
  const sortByStart = (nodes: SpanNodeData[]) => {
    nodes.sort((a, b) => a.span.startEpochMillis - b.span.startEpochMillis);
    nodes.forEach((n) => sortByStart(n.children));
  };
  sortByStart(roots);

  // Stamp depth via DFS so nested spans indent correctly.
  let maxDepth = 0;
  const stampDepth = (nodes: SpanNodeData[], depth: number) => {
    nodes.forEach((n) => {
      n.depth = depth;
      maxDepth = Math.max(maxDepth, depth);
      stampDepth(n.children, depth + 1);
    });
  };
  stampDepth(roots, 0);

  return { roots, minStart, totalDuration, maxDepth };
}

function clampPercent(value: number): number {
  if (Number.isNaN(value)) return 0;
  if (value < 0) return 0;
  if (value > 100) return 100;
  return value;
}

function formatAttrValue(value: unknown): string {
  if (value === null || value === undefined) return "—";
  if (typeof value === "string") return value;
  if (typeof value === "number" || typeof value === "boolean") return String(value);
  try {
    return JSON.stringify(value);
  } catch {
    return String(value);
  }
}
