import { useEffect, useState } from 'react';
import GlossarySidebar from './GlossarySidebar';
import LayeredRuleEditor from './LayeredRuleEditor';
import AiDrawer from './AiDrawer';
import { SAMPLE_RULE, GLOSSARY as FALLBACK_GLOSSARY, ANNOTATIONS } from './sampleData';
import { api } from '../../api/rulesApi';
import type { GlossaryEntry } from '../../types';

/**
 * v3.14 Preview UI — 業務側 / 技術側分層編輯介面預覽。
 *
 * 對應 docs/v3.14-schema-evolution.md §7 設計：
 *  • Layered view (progressive disclosure)：L1-L5 五層、預設展開 L1+L2
 *  • Inline annotation：每層每行可釘 BA / IT / 法遵註解
 *  • 字彙表 sidebar：左側、點業務名詞跳到定義
 *  • AI drawer：右側、stub 對話視窗（解釋 / 補完 / 找相關）
 *
 * 入口：URL `?preview=v3.14`
 * 範例規則：case 1 R01（被保人 ID 為身分證但國籍非 TW）
 */
export default function V314Preview({ onExit }: { onExit?: () => void }) {
  const [selectedTermId, setSelectedTermId] = useState<string | null>(null);
  const [aiOpen, setAiOpen] = useState(false);
  const [showAnnotations, setShowAnnotations] = useState(true);
  const [glossary, setGlossary] = useState<GlossaryEntry[]>(FALLBACK_GLOSSARY as unknown as GlossaryEntry[]);
  const [glossarySource, setGlossarySource] = useState<'fallback' | 'api'>('fallback');

  // v3.14 Phase A：載入字彙表（API → fallback to embedded sample）
  useEffect(() => {
    let cancelled = false;
    api.getGlossary().then((entries) => {
      if (cancelled) return;
      if (entries.length > 0) {
        setGlossary(entries);
        setGlossarySource('api');
      }
    }).catch(() => {
      /* keep fallback */
    });
    return () => { cancelled = true; };
  }, []);

  return (
    <div className="min-h-screen flex flex-col">
      {/* ─────────── Top thin bar: v3.14 Preview 標記 ─────────── */}
      <div
        className="
          sticky top-16 z-40
          dark:bg-group-green-700/10 bg-accent/5
          border-b dark:border-accent/30 border-accent/40
        "
      >
        <div className="max-w-[1600px] mx-auto px-4 sm:px-6 py-2 flex items-center justify-between gap-4">
          <div className="flex items-center gap-3 min-w-0">
            <span
              className="
                px-2 py-0.5 rounded text-[10px] font-bold tracking-wider uppercase
                bg-accent text-white
              "
            >
              v3.14 PREVIEW
            </span>
            <span className="text-xs dark:text-text-secondary text-light-text-secondary truncate">
              業務側 / 技術側分層編輯介面 · 設計階段 mock — <strong>非生產 UI</strong>
              <span className="ml-2 px-1.5 py-0 rounded text-[9px] font-medium dark:bg-surface-2 dark:text-text-tertiary bg-light-surface-2 text-light-text-tertiary">
                字彙表：{glossarySource === 'api' ? `API (${glossary.length})` : `fallback (${glossary.length})`}
              </span>
            </span>
          </div>
          <div className="flex items-center gap-2 shrink-0">
            <button
              onClick={() => setShowAnnotations(!showAnnotations)}
              className={`
                px-2.5 py-1 rounded-md text-[11px] font-medium transition-colors cursor-pointer
                ${showAnnotations
                  ? 'dark:bg-accent/20 dark:text-accent bg-accent/10 text-accent'
                  : 'dark:bg-surface-2 dark:text-text-tertiary bg-light-surface-2 text-light-text-tertiary'}
              `}
              title="顯示/隱藏 inline annotation"
            >
              {showAnnotations ? '✓ 註解' : '○ 註解'}
            </button>
            <button
              onClick={() => setAiOpen(!aiOpen)}
              className={`
                px-2.5 py-1 rounded-md text-[11px] font-medium transition-colors cursor-pointer
                ${aiOpen
                  ? 'dark:bg-accent/20 dark:text-accent bg-accent/10 text-accent'
                  : 'dark:bg-surface-2 dark:text-text-tertiary bg-light-surface-2 text-light-text-tertiary'}
              `}
              title="切換 AI 助手"
            >
              {aiOpen ? '✓ AI 助手' : '○ AI 助手'}
            </button>
            {onExit && (
              <button
                onClick={onExit}
                className="
                  px-2.5 py-1 rounded-md text-[11px] font-medium cursor-pointer
                  dark:bg-surface-2 dark:text-text-secondary dark:hover:bg-surface-3
                  bg-light-surface-2 text-light-text-secondary hover:bg-light-surface-3
                  transition-colors
                "
              >
                ← 回主介面
              </button>
            )}
          </div>
        </div>
      </div>

      {/* ─────────── 3-column main area ─────────── */}
      <div className="flex-1 max-w-[1600px] mx-auto w-full px-4 sm:px-6 py-4">
        <div
          className={`
            grid gap-4 transition-all
            ${aiOpen
              ? 'grid-cols-1 lg:grid-cols-[280px_1fr_340px]'
              : 'grid-cols-1 lg:grid-cols-[280px_1fr]'}
          `}
        >
          {/* 左：字彙表 */}
          <aside className="lg:sticky lg:top-32 lg:self-start lg:max-h-[calc(100vh-9rem)] lg:overflow-y-auto">
            <GlossarySidebar
              glossary={glossary as never}
              selectedTermId={selectedTermId}
              onSelectTerm={setSelectedTermId}
            />
          </aside>

          {/* 中：分層編輯器 */}
          <main className="min-w-0">
            <LayeredRuleEditor
              rule={SAMPLE_RULE}
              glossary={glossary as never}
              annotations={ANNOTATIONS}
              showAnnotations={showAnnotations}
              highlightTermId={selectedTermId}
              onClearHighlight={() => setSelectedTermId(null)}
            />
          </main>

          {/* 右：AI drawer */}
          {aiOpen && (
            <aside className="lg:sticky lg:top-32 lg:self-start lg:max-h-[calc(100vh-9rem)] lg:overflow-y-auto">
              <AiDrawer onClose={() => setAiOpen(false)} />
            </aside>
          )}
        </div>

        {/* footer 註記 */}
        <div className="mt-8 text-center text-[10px] dark:text-text-tertiary/60 text-light-text-tertiary/60">
          v3.14 Preview · 對應 design doc `docs/v3.14-schema-evolution.md` §7 UI/UX 推薦
        </div>
      </div>
    </div>
  );
}
