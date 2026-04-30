import { motion, AnimatePresence } from "framer-motion";
import { X, Wifi, Zap, Hash, Eye } from "lucide-react";
import "./SettingsModal.css";

export default function SettingsModal({
  open,
  onClose,
  sessionId,
}: {
  open: boolean;
  onClose: () => void;
  sessionId: string | null;
}) {
  if (!open) return null;

  const items = [
    { icon: <Wifi size={14} />, label: "API Base URL", value: window.location.origin + "/api" },
    { icon: <Zap size={14} />, label: "流式输出", value: "启用 (SSE)" },
    { icon: <Hash size={14} />, label: "当前会话", value: sessionId ?? "—" },
    { icon: <Eye size={14} />, label: "Trace 展示", value: "启用" },
  ];

  return (
    <AnimatePresence>
      <motion.div
        className="sm-overlay"
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        exit={{ opacity: 0 }}
        onClick={onClose}
      >
        <motion.div
          className="sm-card"
          initial={{ opacity: 0, y: 30, scale: 0.95 }}
          animate={{ opacity: 1, y: 0, scale: 1 }}
          exit={{ opacity: 0, y: 30, scale: 0.95 }}
          transition={{ duration: 0.25 }}
          onClick={(e) => e.stopPropagation()}
        >
          <div className="sm-header">
            <h2>运行配置</h2>
            <button type="button" className="sm-close" onClick={onClose}>
              <X size={18} />
            </button>
          </div>
          <div className="sm-body">
            <p className="sm-note">当前为只读展示，配置由后端管理。</p>
            <div className="sm-list">
              {items.map((item) => (
                <div key={item.label} className="sm-item">
                  <div className="sm-item-left">
                    {item.icon}
                    <span className="sm-item-label">{item.label}</span>
                  </div>
                  <span className="sm-item-val">{item.value}</span>
                </div>
              ))}
            </div>
          </div>
        </motion.div>
      </motion.div>
    </AnimatePresence>
  );
}
