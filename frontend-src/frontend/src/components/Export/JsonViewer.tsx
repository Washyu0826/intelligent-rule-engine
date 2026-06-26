import { useState, useMemo, useCallback, useEffect, useRef } from 'react';
import { motion } from 'framer-motion';
import type { RuleEnvelope } from '../../types';

// ════════════════════════════════════════════
// Spring config
// ════════════════════════════════════════════
const SPRING = { type: 'spring' as const, stiffness: 300, damping: 30 };

// ════════════════════════════════════════════
// Props
// ════════════════════════════════════════════
export interface JsonViewerProps {
  envelope: RuleEnvelope;
}

// ════════════════════════════════════════════
// Token colors
// ════════════════════════════════════════════
const tokenColors = {
  key:     'dark:text-blue-400 text-blue-600',
  string:  'dark:text-emerald-400 text-emerald-600',
  number:  'dark:text-amber-400 text-amber-600',
  boolean: 'dark:text-violet-400 text-violet-600',
  null:    'dark:text-zinc-500 text-zinc-400',
  punct:   'dark:text-text-tertiary/60 text-light-text-tertiary/60',
  bracket: 'dark:text-text-tertiary/40 text-light-text-tertiary/40',
};

// ════════════════════════════════════════════
// Search highlight helper
// ════════════════════════════════════════════
function HighlightText({ text, query }: { text: string; query: string }) {
  if (!query) return <>{text}</>;
  const lower = text.toLowerCase();
  const qLower = query.toLowerCase();
  const parts: React.ReactNode[] = [];
  let cursor = 0;
  let idx = lower.indexOf(qLower, cursor);
  let partKey = 0;

  while (idx !== -1) {
    if (idx > cursor) parts.push(<span key={partKey++}>{text.slice(cursor, idx)}</span>);
    parts.push(
      <span key={partKey++} className="bg-amber-500/20 rounded-sm px-0.5">{text.slice(idx, idx + query.length)}</span>,
    );
    cursor = idx + query.length;
    idx = lower.indexOf(qLower, cursor);
  }
  if (cursor < text.length) parts.push(<span key={partKey++}>{text.slice(cursor)}</span>);
  return <>{parts}</>;
}

// ════════════════════════════════════════════
// Collapsible bracket indicator
// ════════════════════════════════════════════
function CollapseToggle({ open, onClick }: { open: boolean; onClick: () => void }) {
  return (
    <button
      onClick={(e) => { e.stopPropagation(); onClick(); }}
      className="
        inline-flex items-center justify-center w-3.5 h-3.5 mr-1
        cursor-pointer select-none shrink-0
        dark:text-text-tertiary/50 dark:hover:text-text-secondary
        text-light-text-tertiary/50 hover:text-light-text-secondary
        transition-colors
      "
    >
      <svg
        width="8" height="8" viewBox="0 0 8 8" fill="currentColor"
        style={{ transform: open ? 'rotate(90deg)' : 'rotate(0deg)', transition: 'transform 0.15s ease' }}
      >
        <path d="M2 1l4 3-4 3z" />
      </svg>
    </button>
  );
}

// ════════════════════════════════════════════
// Recursive JsonNode
// ════════════════════════════════════════════
function JsonNode({
  data,
  path,
  depth,
  searchQuery,
  isLast,
}: {
  data: unknown;
  path: string;
  depth: number;
  searchQuery: string;
  isLast: boolean;
}) {
  const [open, setOpen] = useState(depth < 2);
  const comma = isLast ? '' : ',';

  // Check if this subtree matches search
  const subtreeStr = useMemo(() => JSON.stringify(data), [data]);
  const matchesSearch = !searchQuery || subtreeStr.toLowerCase().includes(searchQuery.toLowerCase());
  const dimClass = searchQuery && !matchesSearch ? 'opacity-20' : '';

  // Object
  if (data !== null && typeof data === 'object' && !Array.isArray(data)) {
    const entries = Object.entries(data as Record<string, unknown>);
    const count = entries.length;

    if (!open) {
      return (
        <span className={dimClass}>
          <CollapseToggle open={false} onClick={() => setOpen(true)} />
          <span className={tokenColors.punct}>{'{'}</span>
          <span className="dark:text-text-tertiary/50 text-light-text-tertiary/50 text-[10px] italic mx-1">
            {count} 項
          </span>
          <span className={tokenColors.punct}>{'}'}{comma}</span>
        </span>
      );
    }

    return (
      <span className={dimClass}>
        <CollapseToggle open={true} onClick={() => setOpen(false)} />
        <span className={tokenColors.punct}>{'{'}</span>
        <div className="ml-5 border-l dark:border-border/30 border-light-border/30 pl-2">
          {entries.map(([key, value], i) => (
            <div key={key} className="flex items-start">
              <span className="shrink-0">
                <span className={tokenColors.key}>
                  <HighlightText text={`"${key}"`} query={searchQuery} />
                </span>
                <span className={tokenColors.punct}>: </span>
              </span>
              <span className="min-w-0">
                <JsonNode
                  data={value}
                  path={`${path}.${key}`}
                  depth={depth + 1}
                  searchQuery={searchQuery}
                  isLast={i === entries.length - 1}
                />
              </span>
            </div>
          ))}
        </div>
        <span className={tokenColors.punct}>{'}'}{comma}</span>
      </span>
    );
  }

  // Array
  if (Array.isArray(data)) {
    const count = data.length;

    if (!open) {
      return (
        <span className={dimClass}>
          <CollapseToggle open={false} onClick={() => setOpen(true)} />
          <span className={tokenColors.punct}>[</span>
          <span className="dark:text-text-tertiary/50 text-light-text-tertiary/50 text-[10px] italic mx-1">
            {count} 項
          </span>
          <span className={tokenColors.punct}>{']'}{comma}</span>
        </span>
      );
    }

    return (
      <span className={dimClass}>
        <CollapseToggle open={true} onClick={() => setOpen(false)} />
        <span className={tokenColors.punct}>[</span>
        <div className="ml-5 border-l dark:border-border/30 border-light-border/30 pl-2">
          {data.map((item, i) => (
            <div key={i}>
              <JsonNode
                data={item}
                path={`${path}[${i}]`}
                depth={depth + 1}
                searchQuery={searchQuery}
                isLast={i === data.length - 1}
              />
            </div>
          ))}
        </div>
        <span className={tokenColors.punct}>{']'}{comma}</span>
      </span>
    );
  }

  // Primitives
  if (typeof data === 'string') {
    return (
      <span className={`${tokenColors.string} ${dimClass}`}>
        <HighlightText text={`"${data}"`} query={searchQuery} />
        <span className={tokenColors.punct}>{comma}</span>
      </span>
    );
  }

  if (typeof data === 'number') {
    const str = String(data);
    return (
      <span className={`${tokenColors.number} ${dimClass}`}>
        <HighlightText text={str} query={searchQuery} />
        <span className={tokenColors.punct}>{comma}</span>
      </span>
    );
  }

  if (typeof data === 'boolean') {
    return (
      <span className={`${tokenColors.boolean} ${dimClass}`}>
        <HighlightText text={String(data)} query={searchQuery} />
        <span className={tokenColors.punct}>{comma}</span>
      </span>
    );
  }

  // null
  return (
    <span className={`${tokenColors.null} ${dimClass}`}>
      null
      <span className={tokenColors.punct}>{comma}</span>
    </span>
  );
}

// ════════════════════════════════════════════
// SearchBar
// ════════════════════════════════════════════
function SearchBar({
  query,
  onQueryChange,
  matchCount,
  activeIndex,
  onNext,
  onPrev,
  onClear,
}: {
  query: string;
  onQueryChange: (q: string) => void;
  matchCount: number;
  activeIndex: number;
  onNext: () => void;
  onPrev: () => void;
  onClear: () => void;
}) {
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key === 'f') {
        e.preventDefault();
        inputRef.current?.focus();
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, []);

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter') {
      if (e.shiftKey) {
        onPrev();
      } else {
        onNext();
      }
    } else if (e.key === 'Escape') {
      onClear();
      inputRef.current?.blur();
    }
  };

  return (
    <div className="
      flex items-center gap-1.5 px-2 py-1 rounded-md
      dark:bg-surface-3 bg-light-surface-3
      border dark:border-border border-light-border
    ">
      <svg width="12" height="12" viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round"
        className="dark:text-text-tertiary text-light-text-tertiary shrink-0">
        <circle cx="5" cy="5" r="3.5" />
        <path d="M8 8l2.5 2.5" />
      </svg>
      <input
        ref={inputRef}
        type="text"
        value={query}
        onChange={(e) => onQueryChange(e.target.value)}
        onKeyDown={handleKeyDown}
        placeholder="搜尋…"
        className="
          w-24 text-[10px] font-mono bg-transparent outline-none
          dark:text-text-secondary dark:placeholder:text-text-tertiary/40
          text-light-text-secondary placeholder:text-light-text-tertiary/40
        "
      />
      {query && (
        <span className="text-[9px] font-mono dark:text-text-tertiary text-light-text-tertiary whitespace-nowrap">
          {matchCount > 0 ? `${activeIndex + 1}/${matchCount}` : '0'}
        </span>
      )}
    </div>
  );
}

// ════════════════════════════════════════════
// Copy Button
// ════════════════════════════════════════════
function CopyButton({ text }: { text: string }) {
  const [copied, setCopied] = useState(false);

  const handleCopy = useCallback(async () => {
    try {
      await navigator.clipboard.writeText(text);
    } catch {
      const ta = document.createElement('textarea');
      ta.value = text;
      ta.style.position = 'fixed';
      ta.style.opacity = '0';
      document.body.appendChild(ta);
      ta.select();
      document.execCommand('copy');
      document.body.removeChild(ta);
    }
    setCopied(true);
  }, [text]);

  useEffect(() => {
    if (copied) {
      const t = setTimeout(() => setCopied(false), 2000);
      return () => clearTimeout(t);
    }
  }, [copied]);

  return (
    <button
      onClick={handleCopy}
      className={`
        inline-flex items-center gap-1.5 text-[10px] font-medium
        px-2.5 py-1 rounded-md cursor-pointer transition-all duration-200
        ${copied
          ? 'bg-success/15 text-success'
          : 'dark:bg-surface-3 dark:text-text-tertiary dark:hover:text-accent bg-light-surface-3 text-light-text-tertiary hover:text-accent'
        }
      `}
    >
      {copied ? (
        <>
          <svg width="12" height="12" viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
            <path d="M2.5 6.5l2.5 2.5 4.5-5" />
          </svg>
          已複製
        </>
      ) : (
        <>
          <svg width="12" height="12" viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round">
            <rect x="4" y="4" width="6.5" height="6.5" rx="1" />
            <path d="M8 4V2.5A1 1 0 007 1.5H2.5a1 1 0 00-1 1V7a1 1 0 001 1H4" />
          </svg>
          複製 JSON
        </>
      )}
    </button>
  );
}

// ════════════════════════════════════════════
// Main Component
// ════════════════════════════════════════════
export default function JsonViewer({ envelope }: JsonViewerProps) {
  const [searchQuery, setSearchQuery] = useState('');
  const [activeMatchIndex, setActiveMatchIndex] = useState(0);

  const jsonStr = useMemo(
    () => JSON.stringify(envelope, null, 2),
    [envelope],
  );

  const lineCount = jsonStr.split('\n').length;
  const byteSize = new Blob([jsonStr]).size;

  // Count matches
  const matchCount = useMemo(() => {
    if (!searchQuery) return 0;
    const qLower = searchQuery.toLowerCase();
    const sLower = jsonStr.toLowerCase();
    let count = 0;
    let idx = sLower.indexOf(qLower);
    while (idx !== -1) {
      count++;
      idx = sLower.indexOf(qLower, idx + 1);
    }
    return count;
  }, [jsonStr, searchQuery]);

  // Clamp active index
  useEffect(() => {
    if (activeMatchIndex >= matchCount) {
      setActiveMatchIndex(Math.max(0, matchCount - 1));
    }
  }, [matchCount, activeMatchIndex]);

  const handleNext = useCallback(() => {
    if (matchCount > 0) setActiveMatchIndex((i) => (i + 1) % matchCount);
  }, [matchCount]);

  const handlePrev = useCallback(() => {
    if (matchCount > 0) setActiveMatchIndex((i) => (i - 1 + matchCount) % matchCount);
  }, [matchCount]);

  const handleClear = useCallback(() => {
    setSearchQuery('');
    setActiveMatchIndex(0);
  }, []);

  return (
    <motion.div
      initial={{ opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      transition={SPRING}
      className="
        rounded-xl border overflow-hidden
        dark:bg-surface-1 dark:border-border bg-white border-light-border
      "
    >
      {/* Header */}
      <div className="
        flex items-center justify-between px-4 py-3
        border-b dark:border-border border-light-border
      ">
        <div className="flex items-center gap-2.5">
          <div className="
            w-7 h-7 rounded-md flex items-center justify-center
            dark:bg-surface-3 bg-light-surface-3
            dark:text-text-tertiary text-light-text-tertiary
          ">
            <svg width="14" height="14" viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round">
              <path d="M4 2L2 4.5 4 7" />
              <path d="M10 2l2 2.5L10 7" />
              <path d="M8 1l-2 6" />
              <rect x="1" y="9" width="12" height="3.5" rx="1" strokeDasharray="2 1.5" />
            </svg>
          </div>
          <div>
            <p className="text-xs font-semibold dark:text-text-primary text-light-text-primary">
              RuleEnvelope 中介模型（JSON）
            </p>
            <p className="text-[10px] font-mono dark:text-text-tertiary text-light-text-tertiary">
              engine-neutral · {lineCount} 行 · {byteSize > 1024 ? `${(byteSize / 1024).toFixed(1)} KB` : `${byteSize} B`}
            </p>
          </div>
        </div>

        <div className="flex items-center gap-2">
          <SearchBar
            query={searchQuery}
            onQueryChange={(q) => { setSearchQuery(q); setActiveMatchIndex(0); }}
            matchCount={matchCount}
            activeIndex={activeMatchIndex}
            onNext={handleNext}
            onPrev={handlePrev}
            onClear={handleClear}
          />
          <span className="text-[9px] font-mono px-1.5 py-0.5 rounded dark:bg-surface-3 dark:text-text-tertiary bg-light-surface-3 text-light-text-tertiary">
            schema {envelope.schemaVersion}
          </span>
          <span className="text-[9px] font-mono px-1.5 py-0.5 rounded dark:bg-surface-3 dark:text-text-tertiary bg-light-surface-3 text-light-text-tertiary">
            prompt {envelope.promptVersion}
          </span>
          <CopyButton text={jsonStr} />
        </div>
      </div>

      <div className="
        px-4 py-2 text-[10px] leading-relaxed
        border-b dark:border-border/50 border-light-border/50
        dark:bg-surface-2/35 bg-light-surface-2/50
        dark:text-text-secondary text-light-text-secondary
      ">
        此 JSON 是規則治理中介模型，可用於審查、測試與 adapter 轉換；不是直接宣稱可部署到特定集團規則引擎的最終檔。
      </div>

      {/* Collapsible JSON tree */}
      <div className="
        overflow-auto max-h-[28rem]
        dark:bg-surface-0 bg-light-surface-2
      ">
        <pre className="text-[11px] font-mono px-4 py-3 min-w-max leading-[1.6rem]">
          <JsonNode
            data={envelope}
            path="$"
            depth={0}
            searchQuery={searchQuery}
            isLast={true}
          />
        </pre>
      </div>
    </motion.div>
  );
}
