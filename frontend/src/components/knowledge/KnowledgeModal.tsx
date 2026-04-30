import { useState, useCallback } from "react";
import { motion, AnimatePresence } from "framer-motion";
import { X, Upload, CheckCircle, AlertCircle } from "lucide-react";
import { writeKnowledge } from "../../api/knowledge";
import "./KnowledgeModal.css";

export default function KnowledgeModal({
  open,
  onClose,
}: {
  open: boolean;
  onClose: () => void;
}) {
  const [question, setQuestion] = useState("");
  const [answer, setAnswer] = useState("");
  const [sourceName, setSourceName] = useState("web-ui");
  const [submitting, setSubmitting] = useState(false);
  const [result, setResult] = useState<"success" | "error" | null>(null);

  const handleSubmit = useCallback(async () => {
    if (!question.trim() || !answer.trim() || submitting) return;
    setSubmitting(true);
    setResult(null);
    try {
      await writeKnowledge(question.trim(), answer.trim(), sourceName.trim() || "web-ui");
      setResult("success");
      setQuestion("");
      setAnswer("");
    } catch {
      setResult("error");
    } finally {
      setSubmitting(false);
    }
  }, [question, answer, sourceName, submitting]);

  if (!open) return null;

  return (
    <AnimatePresence>
      <motion.div
        className="km-overlay"
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        exit={{ opacity: 0 }}
        onClick={onClose}
      >
        <motion.div
          className="km-card"
          initial={{ opacity: 0, y: 30, scale: 0.95 }}
          animate={{ opacity: 1, y: 0, scale: 1 }}
          exit={{ opacity: 0, y: 30, scale: 0.95 }}
          transition={{ duration: 0.25 }}
          onClick={(e) => e.stopPropagation()}
        >
          <div className="km-header">
            <h2>写入知识库</h2>
            <button type="button" className="km-close" onClick={onClose}>
              <X size={18} />
            </button>
          </div>

          <div className="km-body">
            <label className="km-label">问题</label>
            <input
              className="km-input"
              placeholder="输入问题"
              value={question}
              onChange={(e) => setQuestion(e.target.value)}
            />

            <label className="km-label">答案</label>
            <textarea
              className="km-textarea"
              rows={4}
              placeholder="输入答案"
              value={answer}
              onChange={(e) => setAnswer(e.target.value)}
            />

            <label className="km-label">来源名称</label>
            <input
              className="km-input"
              placeholder="例如：project-notes.md"
              value={sourceName}
              onChange={(e) => setSourceName(e.target.value)}
            />

            {result && (
              <div className={`km-result ${result}`}>
                {result === "success"
                  ? <><CheckCircle size={16} /> 写入成功并已索引</>
                  : <><AlertCircle size={16} /> 写入失败，请重试</>}
              </div>
            )}
          </div>

          <div className="km-footer">
            <button type="button" className="km-btn secondary" onClick={onClose}>取消</button>
            <motion.button
              type="button"
              className="km-btn primary"
              onClick={handleSubmit}
              disabled={!question.trim() || !answer.trim() || submitting}
              whileHover={{ y: -1 }}
              whileTap={{ scale: 0.97 }}
            >
              <Upload size={16} />
              {submitting ? "写入中…" : "写入"}
            </motion.button>
          </div>
        </motion.div>
      </motion.div>
    </AnimatePresence>
  );
}
