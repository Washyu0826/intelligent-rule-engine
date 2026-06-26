import { useRef, useState } from 'react';

interface Message {
  id: string;
  role: 'user' | 'ai';
  content: string;
}

const STARTER_PROMPTS = [
  '解釋這條規則做什麼',
  '為什麼要做這個檢核？',
  '找所有與「國籍」相關的規則',
  '這條規則如果只看身分證會漏掉什麼？',
];

// 預設假回應（v3.14 Preview 是視覺 mock、不接 LLM）
const FAKE_RESPONSE: Record<string, string> = {
  '解釋這條規則做什麼':
    '這條規則 R01 屬於 KYC / CDD 資料品質防呆。當客戶填寫的「被保人 ID」看起來像中華民國身分證（A123456789 格式 + 校驗碼通過）但「國籍別」卻不是 TW，系統會拋出訊息 1.1 提示業務員確認 — 通常是業務員填錯國籍別欄位。\n\n出處：金管會 AML 指引 §3、保險法 §3、§4。',
  '為什麼要做這個檢核？':
    '主要動機是 KYC/CDD（客戶身分確認）資料品質防呆：\n\n• 若外籍客戶的國籍別被誤填為 TW，會漏掉 CRS 申報\n• 若本國客戶被填非 TW，會錯誤觸發 FATCA 流程\n• 屬資料品質規則，不是法規明文要求，但符合 KYC/CDD 內控自律精神。',
  '找所有與「國籍」相關的規則':
    '在當前規則庫中找到 4 條與「國籍」相關規則：\n\n• R01 被保人ID 為身分證 + 國籍非 TW → 拋 1.1\n• R02 被保人ID 不為身分證 + 國籍為 TW → 拋 1.2\n• R03 要保人ID 為身分證 + 國籍非 TW → 拋 1.3\n• R04 要保人ID 不為身分證 + 國籍為 TW → 拋 1.4\n\n此 4 條規則形成完整的 ID-國籍交叉檢核 family。',
  '這條規則如果只看身分證會漏掉什麼？':
    '此 R01 規則目前在 schema 上有兩個未解決點：\n\n1. 新式外來人口統一證號（2021 起、第 2 碼 8/9）格式比照身分證 — 若不排除，會誤判外籍客戶為「身分證格式」。已開出問題待 BU 端確認。\n\n2. 護照號碼各國格式不一，目前 caller 端 RocIdValidator 只驗 ROC ID，其他證號統一視為非 ROC 格式（無法驗證有效性）。',
};

export default function AiDrawer({ onClose }: { onClose?: () => void }) {
  const [messages, setMessages] = useState<Message[]>([
    {
      id: 'sys-1',
      role: 'ai',
      content:
        '👋 我是 v3.14 規則 AI 助手（mock）。\n\n我可以協助解釋規則邏輯、找相關規則、產生測試案例、補完技術細節。試試左下方的快速問題，或自己輸入。',
    },
  ]);
  const [input, setInput] = useState('');
  const [thinking, setThinking] = useState(false);
  const nextMessageId = useRef(1);

  const send = (text: string) => {
    if (!text.trim()) return;
    const userMsg: Message = { id: `u-${nextMessageId.current++}`, role: 'user', content: text };
    setMessages((prev) => [...prev, userMsg]);
    setInput('');
    setThinking(true);
    setTimeout(() => {
      const reply: Message = {
        id: `ai-${nextMessageId.current++}`,
        role: 'ai',
        content:
          FAKE_RESPONSE[text] ??
          `（mock 回應）這是 v3.14 Preview 的 AI 助手示範。實際系統會呼叫後端 LLM（Claude / Gemini / Ollama / GPT）+ v3.6-v3.10 品質防線（Judge / Grounding / Confidence）回答。\n\n你的問題：「${text}」`,
      };
      setMessages((prev) => [...prev, reply]);
      setThinking(false);
    }, 600);
  };

  return (
    <section
      className="
        rounded-xl border
        dark:bg-surface-1 dark:border-border bg-white border-light-border
        shadow-sm overflow-hidden flex flex-col
      "
      style={{ minHeight: '500px' }}
      aria-label="AI 助手"
    >
      {/* Header */}
      <header className="
        px-3 py-2 border-b dark:border-border border-light-border
        flex items-center justify-between
      ">
        <div className="flex items-center gap-2">
          <span className="
            w-6 h-6 rounded-full flex items-center justify-center text-[12px]
            bg-accent text-white
          ">
            ✨
          </span>
          <div>
            <h3 className="text-xs font-bold dark:text-text-primary text-light-text-primary">
              規則 AI 助手
            </h3>
            <div className="text-[9px] dark:text-text-tertiary text-light-text-tertiary">
              v3.14 Preview · stub mock 模式
            </div>
          </div>
        </div>
        {onClose && (
          <button
            onClick={onClose}
            className="
              text-xs dark:text-text-tertiary text-light-text-tertiary
              hover:dark:text-text-primary hover:text-light-text-primary
              cursor-pointer
            "
            aria-label="關閉 AI 助手"
          >
            ✕
          </button>
        )}
      </header>

      {/* Messages */}
      <div className="flex-1 overflow-y-auto p-3 space-y-2.5">
        {messages.map((m) => (
          <div key={m.id} className={`flex ${m.role === 'user' ? 'justify-end' : 'justify-start'}`}>
            <div
              className={`
                max-w-[90%] px-2.5 py-1.5 rounded-lg text-[11.5px] leading-relaxed whitespace-pre-wrap
                ${m.role === 'user'
                  ? 'bg-accent text-white rounded-br-sm'
                  : 'dark:bg-surface-2 dark:text-text-primary bg-light-surface-2 text-light-text-primary rounded-bl-sm'}
              `}
            >
              {m.content}
            </div>
          </div>
        ))}
        {thinking && (
          <div className="flex justify-start">
            <div className="
              px-2.5 py-1.5 rounded-lg text-xs animate-pulse
              dark:bg-surface-2 dark:text-text-tertiary bg-light-surface-2 text-light-text-tertiary
            ">
              思考中…
            </div>
          </div>
        )}
      </div>

      {/* Starter prompts */}
      <div className="px-3 py-2 border-t dark:border-border border-light-border space-y-1">
        <div className="text-[9px] uppercase tracking-wider dark:text-text-tertiary text-light-text-tertiary">
          快速問題
        </div>
        <div className="flex flex-wrap gap-1">
          {STARTER_PROMPTS.map((p) => (
            <button
              key={p}
              onClick={() => send(p)}
              disabled={thinking}
              className="
                px-1.5 py-0.5 text-[10px] rounded transition-colors cursor-pointer
                dark:bg-surface-2 dark:text-text-secondary dark:hover:bg-surface-3
                bg-light-surface-2 text-light-text-secondary hover:bg-light-surface-3
                disabled:opacity-50 disabled:cursor-not-allowed
              "
            >
              {p}
            </button>
          ))}
        </div>
      </div>

      {/* Input */}
      <div className="px-3 py-2 border-t dark:border-border border-light-border">
        <form
          onSubmit={(e) => {
            e.preventDefault();
            send(input);
          }}
          className="flex gap-1.5"
        >
          <input
            value={input}
            onChange={(e) => setInput(e.target.value)}
            placeholder="輸入問題…"
            disabled={thinking}
            className="
              flex-1 px-2 py-1 rounded text-[11.5px]
              dark:bg-surface-2 dark:border-border dark:text-text-primary
              bg-light-surface-2 border-light-border text-light-text-primary
              border focus:outline-none focus:ring-1 focus:ring-accent/40
              disabled:opacity-50
            "
          />
          <button
            type="submit"
            disabled={!input.trim() || thinking}
            className="
              px-2.5 py-1 rounded text-[11px] font-medium cursor-pointer
              bg-accent text-white hover:bg-accent-dim
              disabled:opacity-40 disabled:cursor-not-allowed
              transition-colors
            "
          >
            送出
          </button>
        </form>
      </div>
    </section>
  );
}
