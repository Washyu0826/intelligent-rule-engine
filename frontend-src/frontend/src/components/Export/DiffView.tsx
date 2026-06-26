import { useMemo } from 'react';
import { motion } from 'framer-motion';
import type { RuleEnvelope } from '../../types';
import SemanticTreeDiff from './SemanticTreeDiff';
import BehavioralTableDiff from './BehavioralTableDiff';
import StructuralTableDiff from './StructuralTableDiff';

// ════════════════════════════════════════════
// Spring config
// ════════════════════════════════════════════
const SPRING = { type: 'spring' as const, stiffness: 300, damping: 30 };

// ════════════════════════════════════════════
// Props
// ════════════════════════════════════════════
export interface DiffViewProps {
  current: RuleEnvelope;
  previous?: RuleEnvelope;
}

// ════════════════════════════════════════════
// Diff types
// ════════════════════════════════════════════
type DiffType = 'same' | 'added' | 'removed' | 'changed';

interface DiffLine {
  type: DiffType;
  leftLineNum?: number;
  rightLineNum?: number;
  leftContent: string;
  rightContent: string;
}

// ════════════════════════════════════════════
// LCS diff algorithm
// ════════════════════════════════════════════
function lcsLineDiff(leftLines: string[], rightLines: string[]): DiffLine[] {
  const m = leftLines.length;
  const n = rightLines.length;

  // Build DP table
  const dp: number[][] = Array.from({ length: m + 1 }, () => new Array(n + 1).fill(0));
  for (let i = 1; i <= m; i++) {
    for (let j = 1; j <= n; j++) {
      if (leftLines[i - 1] === rightLines[j - 1]) {
        dp[i][j] = dp[i - 1][j - 1] + 1;
      } else {
        dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
      }
    }
  }

  // Backtrack
  const raw: { type: 'same' | 'added' | 'removed'; leftIdx?: number; rightIdx?: number }[] = [];
  let i = m;
  let j = n;

  while (i > 0 || j > 0) {
    if (i > 0 && j > 0 && leftLines[i - 1] === rightLines[j - 1]) {
      raw.push({ type: 'same', leftIdx: i - 1, rightIdx: j - 1 });
      i--;
      j--;
    } else if (j > 0 && (i === 0 || dp[i][j - 1] >= dp[i - 1][j])) {
      raw.push({ type: 'added', rightIdx: j - 1 });
      j--;
    } else {
      raw.push({ type: 'removed', leftIdx: i - 1 });
      i--;
    }
  }

  raw.reverse();

  // Convert to DiffLine[] and detect changed pairs (adjacent removed+added)
  const result: DiffLine[] = [];
  let idx = 0;

  while (idx < raw.length) {
    const entry = raw[idx];

    if (entry.type === 'same') {
      result.push({
        type: 'same',
        leftLineNum: (entry.leftIdx ?? 0) + 1,
        rightLineNum: (entry.rightIdx ?? 0) + 1,
        leftContent: leftLines[entry.leftIdx!],
        rightContent: rightLines[entry.rightIdx!],
      });
      idx++;
      continue;
    }

    // Collect consecutive removed then added as "changed" pairs
    if (entry.type === 'removed') {
      const removedBatch: number[] = [];
      while (idx < raw.length && raw[idx].type === 'removed') {
        removedBatch.push(raw[idx].leftIdx!);
        idx++;
      }
      const addedBatch: number[] = [];
      while (idx < raw.length && raw[idx].type === 'added') {
        addedBatch.push(raw[idx].rightIdx!);
        idx++;
      }

      const pairCount = Math.min(removedBatch.length, addedBatch.length);

      // Paired as changed
      for (let p = 0; p < pairCount; p++) {
        result.push({
          type: 'changed',
          leftLineNum: removedBatch[p] + 1,
          rightLineNum: addedBatch[p] + 1,
          leftContent: leftLines[removedBatch[p]],
          rightContent: rightLines[addedBatch[p]],
        });
      }

      // Remaining removed
      for (let p = pairCount; p < removedBatch.length; p++) {
        result.push({
          type: 'removed',
          leftLineNum: removedBatch[p] + 1,
          leftContent: leftLines[removedBatch[p]],
          rightContent: '',
        });
      }

      // Remaining added
      for (let p = pairCount; p < addedBatch.length; p++) {
        result.push({
          type: 'added',
          rightLineNum: addedBatch[p] + 1,
          leftContent: '',
          rightContent: rightLines[addedBatch[p]],
        });
      }
      continue;
    }

    // Standalone added
    if (entry.type === 'added') {
      result.push({
        type: 'added',
        rightLineNum: (entry.rightIdx ?? 0) + 1,
        leftContent: '',
        rightContent: rightLines[entry.rightIdx!],
      });
      idx++;
    }
  }

  return result;
}

// ════════════════════════════════════════════
// Word-level inline diff for changed lines
// ════════════════════════════════════════════
function tokenize(line: string): string[] {
  return line.match(/\S+|\s+/g) ?? [line];
}

function InlineDiff({
  left,
  right,
  side,
}: {
  left: string;
  right: string;
  side: 'left' | 'right';
}) {
  const leftTokens = tokenize(left);
  const rightTokens = tokenize(right);
  const tokens = side === 'left' ? leftTokens : rightTokens;
  const other = side === 'left' ? rightTokens : leftTokens;
  const highlightClass = side === 'left' ? 'bg-amber-500/20 rounded-sm' : 'bg-blue-500/20 rounded-sm';

  return (
    <>
      {tokens.map((token, i) => {
        const isChanged = i >= other.length || token !== other[i];
        return (
          <span key={i} className={isChanged ? highlightClass : ''}>
            {token}
          </span>
        );
      })}
    </>
  );
}

// ════════════════════════════════════════════
// Diff line colors
// ════════════════════════════════════════════
const diffColors: Record<DiffType, {
  leftBg: string;
  rightBg: string;
  leftText: string;
  rightText: string;
  gutter: string;
}> = {
  same: {
    leftBg: '',
    rightBg: '',
    leftText: 'dark:text-text-secondary text-light-text-secondary',
    rightText: 'dark:text-text-secondary text-light-text-secondary',
    gutter: 'dark:text-text-tertiary/30 text-light-text-tertiary/30',
  },
  added: {
    leftBg: '',
    rightBg: 'dark:bg-emerald-500/8 bg-emerald-500/8',
    leftText: 'dark:text-text-tertiary/20 text-light-text-tertiary/20',
    rightText: 'text-emerald-400',
    gutter: 'text-emerald-400/60',
  },
  removed: {
    leftBg: 'dark:bg-red-500/8 bg-red-500/8',
    rightBg: '',
    leftText: 'text-red-400',
    rightText: 'dark:text-text-tertiary/20 text-light-text-tertiary/20',
    gutter: 'text-red-400/60',
  },
  changed: {
    leftBg: 'dark:bg-amber-500/6 bg-amber-500/6',
    rightBg: 'dark:bg-blue-500/6 bg-blue-500/6',
    leftText: 'text-amber-400',
    rightText: 'text-blue-400',
    gutter: 'text-amber-400/60',
  },
};

// ════════════════════════════════════════════
// Main Component
// ════════════════════════════════════════════
export default function DiffView({ current, previous }: DiffViewProps) {
  const leftJson = useMemo(
    () => JSON.stringify(previous ?? null, null, 2),
    [previous],
  );
  const rightJson = useMemo(() => JSON.stringify(current, null, 2), [current]);
  const diff = useMemo(
    () => (previous ? lcsLineDiff(leftJson.split('\n'), rightJson.split('\n')) : []),
    [leftJson, rightJson, previous],
  );
  const addedCount = diff.filter(d => d.type === 'added').length;
  const removedCount = diff.filter(d => d.type === 'removed').length;
  const changedCount = diff.filter(d => d.type === 'changed').length;

  // ── No previous → Placeholder ──
  if (!previous) {
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
        <div className="px-5 py-8 text-center space-y-3">
          <div className="
            w-12 h-12 rounded-xl mx-auto
            flex items-center justify-center
            dark:bg-surface-3 bg-light-surface-3
            dark:text-text-tertiary text-light-text-tertiary
          ">
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
              <rect x="3" y="3" width="7" height="18" rx="1.5" />
              <rect x="14" y="3" width="7" height="18" rx="1.5" />
              <path d="M6 8h1M6 11h1M6 14h1" opacity="0.5" />
              <path d="M17 8h1M17 11h1M17 14h1" opacity="0.5" />
            </svg>
          </div>
          <div>
            <p className="text-sm font-semibold dark:text-text-secondary text-light-text-secondary">
              版本差異比較
            </p>
            <p className="text-xs dark:text-text-tertiary text-light-text-tertiary mt-1 max-w-xs mx-auto">
              本功能用於比對兩次生成的規則差異。請先在「總覽」分頁的「生成歷史」中選擇先前版本，再回到此處檢視差異。
            </p>
          </div>
        </div>
      </motion.div>
    );
  }

  const bothTrees = current.ruleType === 'DecisionTree' && previous.ruleType === 'DecisionTree';
  const bothTables = current.ruleType === 'DecisionTable' && previous.ruleType === 'DecisionTable';

  return (
    <div className="space-y-4">
      {/* 語意結構 diff（方向③）：兩邊皆 DecisionTree 時顯示，與下方文字 diff 互補 */}
      {bothTrees && <SemanticTreeDiff before={previous} after={current} />}

      {/* DecisionTable：結構 diff（改了什麼規則）+ 行為 diff（造成什麼回歸），互補 */}
      {bothTables && <StructuralTableDiff before={previous} after={current} />}
      {bothTables && <BehavioralTableDiff before={previous} after={current} />}

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
              <rect x="1" y="1" width="5" height="12" rx="1" />
              <rect x="8" y="1" width="5" height="12" rx="1" />
            </svg>
          </div>
          <div>
            <p className="text-xs font-semibold dark:text-text-primary text-light-text-primary">
              差異比較
            </p>
            <p className="text-[10px] dark:text-text-tertiary text-light-text-tertiary">
              {addedCount + removedCount + changedCount} 行差異
            </p>
          </div>
        </div>

        <div className="flex items-center gap-1.5">
          {addedCount > 0 && (
            <span className="text-[10px] font-mono px-1.5 py-0.5 rounded bg-emerald-500/10 text-emerald-400">
              +{addedCount}
            </span>
          )}
          {removedCount > 0 && (
            <span className="text-[10px] font-mono px-1.5 py-0.5 rounded bg-red-500/10 text-red-400">
              -{removedCount}
            </span>
          )}
          {changedCount > 0 && (
            <span className="text-[10px] font-mono px-1.5 py-0.5 rounded bg-amber-500/10 text-amber-400">
              ~{changedCount}
            </span>
          )}
        </div>
      </div>

      {/* Diff content */}
      <div className="overflow-auto max-h-96 dark:bg-surface-0 bg-light-surface-2">
        {/* Column headers */}
        <div className="grid grid-cols-2 divide-x dark:divide-border/30 divide-light-border/30 sticky top-0 z-10 dark:bg-surface-2 bg-light-surface-3">
          <div className="
            px-3 py-1.5 text-[9px] uppercase tracking-wider font-medium
            dark:text-text-tertiary text-light-text-tertiary
          ">
            先前版本
          </div>
          <div className="
            px-3 py-1.5 text-[9px] uppercase tracking-wider font-medium
            dark:text-text-tertiary text-light-text-tertiary
          ">
            目前版本
          </div>
        </div>

        {/* Diff lines */}
        {diff.map((line, i) => {
          const colors = diffColors[line.type];
          const isChanged = line.type === 'changed';

          return (
            <div
              key={i}
              className="grid grid-cols-2 divide-x dark:divide-border/10 divide-light-border/10"
            >
              {/* Left */}
              <div className={`flex ${colors.leftBg}`}>
                <span className={`
                  w-8 shrink-0 text-right pr-2 text-[9px] font-mono select-none py-0.5
                  ${colors.gutter}
                `}>
                  {line.leftLineNum ?? ''}
                </span>
                <span className={`text-[10px] font-mono py-0.5 whitespace-pre ${colors.leftText}`}>
                  {isChanged ? (
                    <InlineDiff left={line.leftContent} right={line.rightContent} side="left" />
                  ) : (
                    line.leftContent
                  )}
                </span>
              </div>

              {/* Right */}
              <div className={`flex ${colors.rightBg}`}>
                <span className={`
                  w-8 shrink-0 text-right pr-2 text-[9px] font-mono select-none py-0.5
                  ${colors.gutter}
                `}>
                  {line.rightLineNum ?? ''}
                </span>
                <span className={`text-[10px] font-mono py-0.5 whitespace-pre ${colors.rightText}`}>
                  {isChanged ? (
                    <InlineDiff left={line.leftContent} right={line.rightContent} side="right" />
                  ) : (
                    line.rightContent
                  )}
                </span>
              </div>
            </div>
          );
        })}
      </div>
    </motion.div>
    </div>
  );
}
