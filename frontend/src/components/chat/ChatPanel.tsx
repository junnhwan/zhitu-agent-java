import { Sparkles, Database, Wrench, Brain } from "lucide-react";
import { motion } from "framer-motion";
import ChatMessage from "./ChatMessage";
import type { MessageState } from "../../hooks/types";
import { useAutoScroll } from "../../hooks/useAutoScroll";
import "./ChatPanel.css";

const SUGGESTIONS: { icon: React.ReactNode; title: string; prompt: string }[] = [
  {
    icon: <Sparkles size={16} />,
    title: "能力介绍",
    prompt: "介绍一下你的核心能力，以及背后用到了哪些组件",
  },
  {
    icon: <Database size={16} />,
    title: "知识库检索",
    prompt: "从知识库中检索关于 RAG 检索增强的内容，并总结要点",
  },
  {
    icon: <Wrench size={16} />,
    title: "工具调用",
    prompt: "帮我查一下当前时间，并说明你是怎么拿到的",
  },
  {
    icon: <Brain size={16} />,
    title: "长期记忆",
    prompt: "请记住：我是 Java 后端工程师，正在学习 AI Agent",
  },
];

export default function ChatPanel({
  messages,
  onSuggestionClick,
}: {
  messages: MessageState[];
  onSuggestionClick?: (prompt: string) => void;
}) {
  const scrollRef = useAutoScroll(messages);

  return (
    <div className="cp" ref={scrollRef}>
      <div className="cp-inner">
        {messages.length === 0 && (
          <div className="cp-empty">
            <h2 className="cp-empty-title">想从哪里开始？</h2>
            <p className="cp-empty-sub">点一个常用场景，或者直接在下方输入</p>
            <div className="cp-suggestions">
              {SUGGESTIONS.map((s, i) => (
                <motion.button
                  key={s.title}
                  type="button"
                  className="cp-suggestion"
                  initial={{ opacity: 0, y: 12 }}
                  animate={{ opacity: 1, y: 0 }}
                  transition={{ duration: 0.4, delay: i * 0.06 }}
                  whileHover={{ y: -2 }}
                  onClick={() => onSuggestionClick?.(s.prompt)}
                >
                  <span className="cp-suggestion-icon">{s.icon}</span>
                  <span className="cp-suggestion-body">
                    <span className="cp-suggestion-title">{s.title}</span>
                    <span className="cp-suggestion-prompt">{s.prompt}</span>
                  </span>
                </motion.button>
              ))}
            </div>
          </div>
        )}
        {messages.map((msg, i) => (
          <ChatMessage key={i} msg={msg} index={i} />
        ))}
      </div>
    </div>
  );
}
