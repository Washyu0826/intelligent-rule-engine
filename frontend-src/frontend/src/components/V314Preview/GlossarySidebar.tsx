import { useMemo, useState } from 'react';
import type { GlossaryEntry } from './sampleData';

const CATEGORIES: Array<{ id: GlossaryEntry['category'] | 'all'; label: string }> = [
  { id: 'all', label: '全部' },
  { id: '核保', label: '核保' },
  { id: '商品', label: '商品' },
  { id: '理賠', label: '理賠' },
  { id: '通路', label: '通路' },
  { id: '繳費', label: '繳費' },
  { id: '合規', label: '合規' },
  { id: '精算', label: '精算' },
];

const STATUS_STYLE: Record<GlossaryEntry['status'], { label: string; cls: string }> = {
  active: { label: '使用中', cls: 'dark:bg-success/15 dark:text-success bg-success/10 text-success' },
  deprecated: { label: '已棄用', cls: 'dark:bg-danger/15 dark:text-danger bg-danger/10 text-danger' },
  proposed: { label: '待確認', cls: 'dark:bg-warning/15 dark:text-warning bg-warning/10 text-warning' },
};

interface Props {
  glossary: GlossaryEntry[];
  selectedTermId: string | null;
  onSelectTerm: (id: string | null) => void;
}

export default function GlossarySidebar({ glossary, selectedTermId, onSelectTerm }: Props) {
  const [activeCategory, setActiveCategory] = useState<typeof CATEGORIES[number]['id']>('all');
  const [query, setQuery] = useState('');

  const filtered = useMemo(() => {
    let list = glossary;
    if (activeCategory !== 'all') {
      list = list.filter((g) => g.category === activeCategory);
    }
    if (query.trim()) {
      const q = query.trim().toLowerCase();
      list = list.filter(
        (g) =>
          g.zh_TW.includes(query) ||
          g.en.toLowerCase().includes(q) ||
          g.synonyms?.some((s) => s.includes(query)) ||
          g.definition.includes(query)
      );
    }
    return list;
  }, [glossary, activeCategory, query]);

  return (
    <section
      className="
        rounded-xl border
        dark:bg-surface-1 dark:border-border bg-white border-light-border
        shadow-sm overflow-hidden
      "
      aria-label="領域字彙表"
    >
      <header className="px-3 py-2.5 border-b dark:border-border border-light-border">
        <div className="flex items-center justify-between mb-1.5">
          <h3 className="text-xs font-bold dark:text-text-primary text-light-text-primary">
            領域字彙表
          </h3>
          <span className="text-[10px] dark:text-text-tertiary text-light-text-tertiary tabular-nums">
            {filtered.length} / {glossary.length}
          </span>
        </div>
        <input
          type="text"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="搜尋名詞（中／英／同義）"
          className="
            w-full px-2 py-1 text-xs rounded
            dark:bg-surface-2 dark:border-border dark:text-text-primary dark:placeholder:text-text-tertiary/60
            bg-light-surface-2 border-light-border text-light-text-primary placeholder:text-light-text-tertiary/60
            border focus:outline-none focus:ring-1 focus:ring-accent/40
          "
        />
      </header>

      {/* 分類 tabs */}
      <div className="px-3 py-2 border-b dark:border-border/50 border-light-border/60 flex flex-wrap gap-1">
        {CATEGORIES.map((cat) => (
          <button
            key={cat.id}
            onClick={() => setActiveCategory(cat.id)}
            className={`
              px-1.5 py-0.5 text-[10px] rounded transition-colors cursor-pointer
              ${activeCategory === cat.id
                ? 'dark:bg-accent/20 dark:text-accent bg-accent/15 text-accent font-semibold'
                : 'dark:text-text-tertiary text-light-text-tertiary hover:dark:text-text-secondary hover:text-light-text-secondary'}
            `}
          >
            {cat.label}
          </button>
        ))}
      </div>

      {/* 名詞清單 */}
      <ul className="max-h-[calc(100vh-22rem)] overflow-y-auto py-1">
        {filtered.map((entry) => {
          const selected = entry.id === selectedTermId;
          const statusInfo = STATUS_STYLE[entry.status];
          return (
            <li key={entry.id}>
              <button
                onClick={() => onSelectTerm(selected ? null : entry.id)}
                className={`
                  w-full text-left px-3 py-2 transition-colors cursor-pointer
                  ${selected
                    ? 'dark:bg-accent/10 bg-accent/5 border-l-2 border-accent'
                    : 'border-l-2 border-transparent hover:dark:bg-surface-2 hover:bg-light-surface-2'}
                `}
              >
                <div className="flex items-center justify-between gap-2">
                  <span className="font-semibold text-xs dark:text-text-primary text-light-text-primary">
                    {entry.zh_TW}
                  </span>
                  <span
                    className={`px-1 py-0 text-[9px] rounded font-medium shrink-0 ${statusInfo.cls}`}
                  >
                    {statusInfo.label}
                  </span>
                </div>
                <div className="text-[10px] dark:text-text-tertiary text-light-text-tertiary mt-0.5 truncate">
                  {entry.en}
                  {entry.synonyms && entry.synonyms.length > 0 && (
                    <span className="ml-1">· {entry.synonyms.join(' / ')}</span>
                  )}
                </div>
                {selected && (
                  <div className="mt-2 text-[11px] dark:text-text-secondary text-light-text-secondary leading-relaxed animate-fade-in-up">
                    <div className="mb-1">{entry.definition}</div>
                    <div className="text-[10px] dark:text-text-tertiary text-light-text-tertiary">
                      出處：{entry.source}
                      {entry.fieldCode && <span className="ml-2">技術代碼：<code className="dark:bg-surface-3 bg-light-surface-3 px-1 rounded">{entry.fieldCode}</code></span>}
                    </div>
                    {entry.deprecatedAt && (
                      <div className="mt-1 text-[10px] text-warning">
                        ⚠ {entry.deprecatedAt} 起棄用，請改用「{glossarySuccessorName(entry, glossary)}」
                      </div>
                    )}
                  </div>
                )}
              </button>
            </li>
          );
        })}
        {filtered.length === 0 && (
          <li className="px-3 py-4 text-center text-xs dark:text-text-tertiary text-light-text-tertiary">
            無符合的名詞
          </li>
        )}
      </ul>
    </section>
  );
}

function glossarySuccessorName(entry: GlossaryEntry, all: GlossaryEntry[]) {
  if (!entry.successor) return '—';
  return all.find((candidate) => candidate.id === entry.successor)?.zh_TW ?? entry.successor;
}
