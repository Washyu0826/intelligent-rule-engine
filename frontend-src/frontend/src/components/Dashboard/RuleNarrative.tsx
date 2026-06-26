import { useEffect, useState, useCallback } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import type { BusinessNarrative, RuleEnvelope } from '../../types';
import { api } from '../../api/rulesApi';

const SPRING = { type: 'spring' as const, stiffness: 300, damping: 28 };

export interface RuleNarrativeProps {
  envelope: RuleEnvelope;
  description?: string;
  /** 可選：指定 provider（沿用 Overview 選定的） */
  provider?: string;
  /** 預設自動載入；設 false 可改成按鈕觸發 */
  autoFetch?: boolean;
}

/**
 * v3.12 — 整體業務敘事卡片。
 * 放在 Overview tab 最上方，給非工程使用者（保險業務／精算師）第一眼理解。
 *
 * 設計原則：
 * - 先講結論（summary），再講要點，再講涵蓋與例外，最後給精算師備註
 * - 使用業務語言，不顯示 operator/typeRef 等技術詞（由後端 prompt 保證）
 * - fallback 時誠實告知 LLM 不可用，不假裝有 AI 產出
 */
const RETRY_PROVIDERS = ['claude', 'gemini', 'ollama', 'openai'] as const;
type RetryProvider = (typeof RETRY_PROVIDERS)[number];

export default function RuleNarrative({
  envelope,
  description,
  provider,
  autoFetch = true,
}: RuleNarrativeProps) {
  const [narrative, setNarrative] = useState<BusinessNarrative | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [lastProvider, setLastProvider] = useState<string | undefined>(provider);

  const fetchNarrative = useCallback(async (overrideProvider?: string) => {
    const effective = overrideProvider ?? provider;
    setLoading(true);
    setError(null);
    setLastProvider(effective);
    try {
      const n = await api.narrate(description ?? '', envelope, effective);
      setNarrative(n);
    } catch (e) {
      setError(
        typeof e === 'object' && e && 'message' in e
          ? String((e as { message: unknown }).message)
          : String(e),
      );
    } finally {
      setLoading(false);
    }
  }, [description, envelope, provider]);

  useEffect(() => {
    if (autoFetch) {
      fetchNarrative();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [autoFetch]);

  const isFallback = narrative?.provider === 'fallback';

  return (
    <div
      className="
        rounded-xl border overflow-hidden
        dark:bg-surface-1 dark:border-border
        bg-gradient-to-br from-blue-50 to-white
        border-light-border
      "
    >
      {/* Header */}
      <div className="flex items-center justify-between px-5 py-3 border-b
        dark:border-border border-light-border
        dark:bg-surface-2 bg-white/50">
        <div className="flex items-center gap-2.5">
          <div className="
            w-7 h-7 rounded-lg flex items-center justify-center
            dark:bg-surface-3 bg-blue-100
            dark:text-blue-400 text-blue-600
          ">
            <svg width="14" height="14" viewBox="0 0 16 16" fill="none"
              stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
              <path d="M2 3h12M2 7h12M2 11h8" />
              <circle cx="13" cy="11" r="2" />
            </svg>
          </div>
          <div>
            <h3 className="text-sm font-semibold
              dark:text-text-primary text-light-text-primary">
              業務總覽
            </h3>
            <p className="text-[11px] dark:text-text-tertiary text-light-text-tertiary">
              給業務與精算師看的人話版摘要
            </p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          {narrative?.provider && !isFallback && (
            <span className="text-[10px] px-2 py-1 rounded-md
              dark:bg-surface-3 bg-light-surface-3
              dark:text-text-tertiary text-light-text-tertiary">
              {narrative.provider}
            </span>
          )}
          <button
            onClick={() => fetchNarrative()}
            disabled={loading}
            className="
              text-[11px] px-3 py-1.5 rounded-md font-medium transition-all
              dark:bg-surface-3 dark:hover:bg-surface-4
              bg-light-surface-3 hover:bg-light-surface-4
              dark:text-text-secondary text-light-text-secondary
              disabled:opacity-50 disabled:cursor-not-allowed
            "
          >
            {loading ? '產生中…' : narrative ? '重新產生' : '產生'}
          </button>
        </div>
      </div>

      {/* Body */}
      <div className="p-5">
        <AnimatePresence mode="wait">
          {loading && !narrative && (
            <motion.div
              key="loading"
              initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
              className="space-y-3"
            >
              {[0, 1, 2].map((i) => (
                <div key={i} className="h-4 rounded
                  dark:bg-surface-3 bg-light-surface-3
                  animate-pulse"
                  style={{ width: `${80 - i * 10}%` }} />
              ))}
            </motion.div>
          )}

          {error && (
            <motion.div
              key="error"
              initial={{ opacity: 0 }} animate={{ opacity: 1 }}
              className="space-y-2"
            >
              <p className="text-xs dark:text-danger text-red-600">
                無法載入業務敘事
                {lastProvider ? `（provider=${lastProvider}）` : ''}：{error}
              </p>
              <ProviderRetryButtons
                lastProvider={lastProvider}
                loading={loading}
                onRetry={(p) => fetchNarrative(p)}
              />
            </motion.div>
          )}

          {narrative && !loading && (
            <motion.div
              key="content"
              initial={{ opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              transition={SPRING}
              className="space-y-4"
            >
              {/* Fallback 提示 */}
              {isFallback && (
                <div className="text-[11px] px-3 py-2 rounded-md space-y-2
                  dark:bg-warning/10 bg-amber-50
                  dark:text-warning text-amber-700
                  border dark:border-warning/30 border-amber-200">
                  <p>
                    LLM 服務不可用
                    {lastProvider ? `（provider=${lastProvider}）` : ''}
                    ，以下為結構化摘要；可改用其他 provider 重試
                  </p>
                  <ProviderRetryButtons
                    lastProvider={lastProvider}
                    loading={loading}
                    onRetry={(p) => fetchNarrative(p)}
                  />
                </div>
              )}

              {/* Summary */}
              {narrative.summary && (
                <p className="text-sm leading-relaxed
                  dark:text-text-primary text-light-text-primary">
                  {narrative.summary}
                </p>
              )}

              {/* Highlights */}
              {narrative.highlights && narrative.highlights.length > 0 && (
                <Section title="關鍵要點" icon="star">
                  <ul className="space-y-1.5">
                    {narrative.highlights.map((h, i) => (
                      <li key={i} className="flex gap-2 text-sm
                        dark:text-text-secondary text-light-text-secondary">
                        <span className="text-blue-500 shrink-0">•</span>
                        <span>{h}</span>
                      </li>
                    ))}
                  </ul>
                </Section>
              )}

              {/* Coverage */}
              {narrative.coverage && (
                <Section title="涵蓋範圍" icon="target">
                  <p className="text-sm dark:text-text-secondary text-light-text-secondary">
                    {narrative.coverage}
                  </p>
                </Section>
              )}

              {/* Exceptions */}
              {narrative.exceptions && narrative.exceptions.length > 0 && (
                <Section title="特殊例外" icon="warn">
                  <ul className="space-y-1.5">
                    {narrative.exceptions.map((e, i) => (
                      <li key={i} className="flex gap-2 text-sm
                        dark:text-text-secondary text-light-text-secondary">
                        <span className="text-amber-500 shrink-0">!</span>
                        <span>{e}</span>
                      </li>
                    ))}
                  </ul>
                </Section>
              )}

              {/* Actuarial note */}
              {narrative.actuarialNote && (
                <Section title="精算師備註" icon="note" subdued>
                  <p className="text-xs italic
                    dark:text-text-tertiary text-light-text-tertiary">
                    {narrative.actuarialNote}
                  </p>
                </Section>
              )}
            </motion.div>
          )}

          {!narrative && !loading && !error && (
            <p className="text-xs dark:text-text-tertiary text-light-text-tertiary">
              點「產生」可由 LLM 撰寫業務口吻的規則總覽（本地 Ollama 約需 1–2 分鐘；雲端 provider 數秒）。
            </p>
          )}
        </AnimatePresence>
      </div>
    </div>
  );
}

// ════════════════════════════════════════════
// Section helper
// ════════════════════════════════════════════
function Section({
  title,
  icon,
  subdued,
  children,
}: {
  title: string;
  icon: 'star' | 'target' | 'warn' | 'note';
  subdued?: boolean;
  children: React.ReactNode;
}) {
  const iconEl = ICONS[icon];
  return (
    <div>
      <div className="flex items-center gap-1.5 mb-2">
        <span className={`
          inline-flex items-center justify-center w-4 h-4 rounded
          ${subdued
            ? 'dark:text-text-tertiary text-light-text-tertiary'
            : 'dark:text-blue-400 text-blue-500'}
        `}>
          {iconEl}
        </span>
        <h4 className={`
          text-[11px] uppercase tracking-[0.08em] font-semibold
          ${subdued
            ? 'dark:text-text-tertiary text-light-text-tertiary'
            : 'dark:text-text-primary text-light-text-primary'}
        `}>
          {title}
        </h4>
      </div>
      {children}
    </div>
  );
}

const ICONS: Record<'star' | 'target' | 'warn' | 'note', React.ReactNode> = {
  star: (
    <svg width="11" height="11" viewBox="0 0 12 12" fill="none"
      stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
      <path d="M6 1l1.5 3.2L11 4.7l-2.5 2.4L9 10.5 6 8.9 3 10.5l.5-3.4L1 4.7l3.5-.5z" />
    </svg>
  ),
  target: (
    <svg width="11" height="11" viewBox="0 0 12 12" fill="none"
      stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="6" cy="6" r="5" />
      <circle cx="6" cy="6" r="2.5" />
      <circle cx="6" cy="6" r="0.5" fill="currentColor" />
    </svg>
  ),
  warn: (
    <svg width="11" height="11" viewBox="0 0 12 12" fill="none"
      stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
      <path d="M6 1l5 9H1z" />
      <path d="M6 5v2.5" />
      <circle cx="6" cy="9" r="0.3" fill="currentColor" />
    </svg>
  ),
  note: (
    <svg width="11" height="11" viewBox="0 0 12 12" fill="none"
      stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
      <rect x="2" y="1.5" width="8" height="9" rx="1" />
      <path d="M4 4h4M4 6h4M4 8h2" />
    </svg>
  ),
};

// ════════════════════════════════════════════
// Provider retry buttons（v3.12 — 失敗 / fallback 時讓使用者換 provider 試）
// ════════════════════════════════════════════
function ProviderRetryButtons({
  lastProvider,
  loading,
  onRetry,
}: {
  lastProvider?: string;
  loading: boolean;
  onRetry: (provider: RetryProvider) => void;
}) {
  return (
    <div className="flex flex-wrap gap-1.5">
      <span className="text-[10px] dark:text-text-tertiary text-light-text-tertiary self-center">
        改用：
      </span>
      {RETRY_PROVIDERS.filter((p) => p !== lastProvider).map((p) => (
        <button
          key={p}
          onClick={() => onRetry(p)}
          disabled={loading}
          className="
            text-[10px] font-mono px-2 py-1 rounded border transition-colors
            dark:bg-surface-2 dark:border-border dark:text-text-secondary
            dark:hover:bg-surface-3 dark:hover:text-text-primary
            bg-white border-light-border text-light-text-secondary
            hover:bg-light-surface-2 hover:text-light-text-primary
            disabled:opacity-50 disabled:cursor-not-allowed
          "
        >
          {p}
        </button>
      ))}
    </div>
  );
}
