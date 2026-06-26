import { useMemo, useCallback, useState, useRef, useEffect } from 'react';
import { motion } from 'framer-motion';
import type { RuleEnvelope } from '../../types';
import { parseDecisionTable, type RuleEntry } from '../Dashboard/types';

// ════════════════════════════════════════════
// Spring config
// ════════════════════════════════════════════
const SPRING = { type: 'spring' as const, stiffness: 300, damping: 30 };

// ════════════════════════════════════════════
// Props
// ════════════════════════════════════════════
export interface CsvExporterProps {
  envelope: RuleEnvelope;
}

// ════════════════════════════════════════════
// Build table data (headers + rows)
// ════════════════════════════════════════════
function formatConditionDisplay(cond: { operator: string; value?: unknown; valueRef?: string }): string {
  if (cond.valueRef) return `→ ${cond.valueRef}`;
  if (cond.operator === 'anything') return '*';
  if (cond.operator === 'between' && Array.isArray(cond.value)) {
    return `[${cond.value[0]}, ${cond.value[1]}]`;
  }
  if ((cond.operator === 'in' || cond.operator === 'notIn') && Array.isArray(cond.value)) {
    return `{${(cond.value as unknown[]).join(', ')}}`;
  }
  if (cond.operator === 'isNull' || cond.operator === 'isNotNull') return cond.operator;
  const op = cond.operator === 'equals' ? '' : `${cond.operator} `;
  return `${op}${String(cond.value ?? '')}`;
}

function buildTableData(
  envelope: RuleEnvelope,
  adapterMode: boolean,
): { headers: string[]; rows: string[][] } {
  const table = parseDecisionTable(envelope.rule);
  if (!table) return { headers: [], rows: [] };

  const { inputs, outputs, rules } = table;

  const condHeaders: string[] = [];
  for (const input of inputs) {
    condHeaders.push(`condition:${input.name}`);
    if (adapterMode) {
      condHeaders.push(`condition_op:${input.name}`);
      condHeaders.push(`condition_type:${input.name}`);
      condHeaders.push(`condition_valueRef:${input.name}`);
      condHeaders.push(`fieldCode:${input.name}`);
    }
  }
  const resHeaders = outputs.map(o => `result:${o.name}`);
  const headers = ['ruleId', 'priority', ...condHeaders, ...resHeaders];

  const rows = rules.map((rule: RuleEntry) => {
    const row: string[] = [rule.ruleId, String(rule.priority)];

    for (const input of inputs) {
      const cond = rule.conditions.find(c => c.field === input.name);
      if (!cond) {
        row.push('');
        if (adapterMode) {
          row.push('');
          row.push(input.typeRef);
          row.push('');
          row.push(input.fieldCode ?? '');
        }
      } else {
        row.push(formatConditionDisplay(cond));
        if (adapterMode) {
          row.push(cond.operator);
          row.push(input.typeRef);
          row.push(cond.valueRef ?? '');
          row.push(input.fieldCode ?? '');
        }
      }
    }

    for (const output of outputs) {
      const result = rule.results.find(r => r.field === output.name);
      row.push(result ? String(result.value ?? '') : '');
    }

    return row;
  });

  return { headers, rows };
}

// ════════════════════════════════════════════
// CSV helpers
// ════════════════════════════════════════════
function escapeCsvField(value: string): string {
  if (value.includes(',') || value.includes('"') || value.includes('\n') || value.includes('[')) {
    return `"${value.replace(/"/g, '""')}"`;
  }
  return value;
}

function rowsToCsv(headers: string[], rows: string[][]): string {
  return [
    headers.map(escapeCsvField).join(','),
    ...rows.map(row => row.map(escapeCsvField).join(',')),
  ].join('\n');
}

function downloadCsv(csv: string, filename: string) {
  const blob = new Blob(['\uFEFF' + csv], { type: 'text/csv;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

// ════════════════════════════════════════════
// Editable Cell
// ════════════════════════════════════════════
function EditableCell({
  value,
  isEditing,
  onStartEdit,
  onCommit,
}: {
  value: string;
  isEditing: boolean;
  onStartEdit: () => void;
  onCommit: (val: string) => void;
}) {
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (isEditing && inputRef.current) {
      inputRef.current.focus();
      inputRef.current.select();
    }
  }, [isEditing]);

  if (isEditing) {
    return (
      <input
        ref={inputRef}
        defaultValue={value}
        onBlur={(e) => onCommit(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Enter') {
            onCommit((e.target as HTMLInputElement).value);
          } else if (e.key === 'Escape') {
            onCommit(value); // revert
          }
        }}
        className="
          w-full px-1.5 py-0.5 text-[10px] font-mono
          bg-accent/10 text-accent outline-none rounded
          border border-accent/30
        "
      />
    );
  }

  return (
    <span
      onClick={onStartEdit}
      className="
        block w-full px-1.5 py-0.5 cursor-text rounded
        hover:bg-accent/5 transition-colors
      "
    >
      {value || <span className="opacity-30">—</span>}
    </span>
  );
}

// ════════════════════════════════════════════
// Main Component
// ════════════════════════════════════════════
export default function CsvExporter({ envelope }: CsvExporterProps) {
  const [adapterMode, setAdapterMode] = useState(false);
  const { headers, rows: initialRows } = useMemo(
    () => buildTableData(envelope, adapterMode),
    [envelope, adapterMode],
  );
  const [rows, setRows] = useState<string[][]>([]);
  const [editing, setEditing] = useState<{ r: number; c: number } | null>(null);
  const [downloaded, setDownloaded] = useState(false);
  const [showAll, setShowAll] = useState(false);
  const [editCount, setEditCount] = useState(0);

  // Reset rows when envelope or adapter-mode changes
  useEffect(() => {
    setRows(initialRows.map(r => [...r]));
    setEditCount(0);
    setEditing(null);
  }, [initialRows]);

  const table = useMemo(() => parseDecisionTable(envelope.rule), [envelope.rule]);

  const handleCommit = useCallback((r: number, c: number, val: string) => {
    setRows(prev => {
      const next = prev.map(row => [...row]);
      if (next[r][c] !== val) {
        next[r][c] = val;
        setEditCount(n => n + 1);
      }
      return next;
    });
    setEditing(null);
  }, []);

  const handleDownload = useCallback(() => {
    const csv = rowsToCsv(headers, rows);
    const timestamp = new Date().toISOString().slice(0, 10);
    downloadCsv(csv, `rules-${envelope.ruleType}-${timestamp}.csv`);
    setDownloaded(true);
  }, [headers, rows, envelope.ruleType]);

  useEffect(() => {
    if (downloaded) {
      const t = setTimeout(() => setDownloaded(false), 3000);
      return () => clearTimeout(t);
    }
  }, [downloaded]);

  if (!table || rows.length === 0) {
    if (envelope.ruleType === 'DecisionTree') {
      return (
        <div className="rounded-xl border p-4 text-xs
          dark:bg-surface-1 dark:border-border dark:text-text-tertiary
          bg-white border-light-border text-light-text-tertiary">
          決策樹結構建議以 RuleEnvelope JSON 交付 adapter；CSV 對照表僅適用於決策表型態。
        </div>
      );
    }
    return null;
  }

  const displayRows = showAll ? rows : rows.slice(0, 10);
  const hasMore = rows.length > 10;

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
              <rect x="1.5" y="1.5" width="11" height="11" rx="1.5" />
              <line x1="1.5" y1="5" x2="12.5" y2="5" />
              <line x1="1.5" y1="8.5" x2="12.5" y2="8.5" />
              <line x1="5.5" y1="5" x2="5.5" y2="12.5" />
              <line x1="9.5" y1="5" x2="9.5" y2="12.5" />
            </svg>
          </div>
          <div>
            <p className="text-xs font-semibold dark:text-text-primary text-light-text-primary">
              規則表 CSV（交付對照）
            </p>
            <p className="text-[10px] dark:text-text-tertiary text-light-text-tertiary">
              {rows.length} 條規則 · {headers.length} 欄 · {adapterMode ? '含 adapter metadata' : '簡化檢視'}
            </p>
          </div>
        </div>

        <div className="flex items-center gap-2">
          <button
            onClick={() => setAdapterMode((v) => !v)}
            title={adapterMode
              ? '關閉後僅輸出條件值欄；adapter mode 會多附 operator/typeRef/valueRef/fieldCode 以利接入'
              : '開啟後 CSV 會多附 operator、typeRef、valueRef、fieldCode 欄，供下游 adapter round-trip'}
            className={`
              inline-flex items-center gap-1.5 text-[10px] font-medium
              px-2.5 py-1.5 rounded-lg cursor-pointer transition-all duration-200 border
              ${adapterMode
                ? 'bg-accent/10 text-accent border-accent/30'
                : 'dark:bg-surface-3 dark:border-border bg-light-surface-3 border-light-border dark:text-text-tertiary text-light-text-tertiary'
              }
            `}
          >
            <span className={`
              w-3 h-3 rounded-full inline-flex items-center justify-center text-[8px]
              ${adapterMode ? 'bg-accent text-surface-0' : 'dark:bg-surface-4 bg-light-surface-2'}
            `}>
              {adapterMode ? '✓' : ''}
            </span>
            Adapter metadata
          </button>

        <button
          onClick={handleDownload}
          className={`
            inline-flex items-center gap-1.5 text-[11px] font-medium
            px-3 py-1.5 rounded-lg cursor-pointer transition-all duration-200
            ${downloaded
              ? 'bg-success/15 text-success'
              : 'bg-accent/10 text-accent hover:bg-accent/20 border border-accent/20'
            }
          `}
        >
          {downloaded ? (
            <>
              <svg width="12" height="12" viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
                <path d="M2.5 6.5l2.5 2.5 4.5-5" />
              </svg>
              已下載
            </>
          ) : (
            <>
              <svg width="12" height="12" viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round">
                <path d="M6 2v6M3.5 6L6 8.5 8.5 6" />
                <path d="M2 9.5h8" />
              </svg>
              下載 CSV
            </>
          )}
        </button>
        </div>
      </div>

      {/* Editable Preview Table */}
      <div className="overflow-x-auto">
        <table className="w-full border-collapse text-[10px] font-mono">
          {/* Header row */}
          <thead>
            <tr>
              {/* Row number column */}
              <th className="
                px-2 py-2 text-right whitespace-nowrap
                dark:bg-surface-2 bg-light-surface-2
                dark:text-text-tertiary/40 text-light-text-tertiary/40
                border-b dark:border-border border-light-border
              ">
                #
              </th>
              {headers.map((h, i) => (
                <th
                  key={i}
                  className="
                    px-2 py-2 text-left whitespace-nowrap
                    dark:bg-surface-2 bg-light-surface-2
                    dark:text-text-secondary text-light-text-secondary
                    font-semibold
                    border-b dark:border-border border-light-border
                  "
                >
                  {h}
                </th>
              ))}
            </tr>
          </thead>

          {/* Data rows */}
          <tbody>
            {displayRows.map((row, rIdx) => (
              <tr
                key={rIdx}
                className={`
                  transition-colors
                  ${rIdx % 2 === 0
                    ? 'dark:bg-surface-1 bg-white'
                    : 'dark:bg-surface-1/50 bg-light-surface-1/50'
                  }
                  dark:hover:bg-surface-2/60 hover:bg-light-surface-2/60
                  border-b dark:border-border/30 border-light-border/30
                `}
              >
                {/* Row number */}
                <td className="
                  px-2 py-1.5 text-right whitespace-nowrap
                  dark:text-text-tertiary/30 text-light-text-tertiary/30
                  select-none
                ">
                  {rIdx + 1}
                </td>
                {row.map((cell, cIdx) => (
                  <td
                    key={cIdx}
                    className="
                      px-1 py-1
                      dark:text-text-secondary text-light-text-secondary
                      whitespace-nowrap
                    "
                  >
                    <EditableCell
                      value={cell}
                      isEditing={editing?.r === rIdx && editing?.c === cIdx}
                      onStartEdit={() => setEditing({ r: rIdx, c: cIdx })}
                      onCommit={(val) => handleCommit(rIdx, cIdx, val)}
                    />
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* Show more button */}
      {hasMore && (
        <div className="border-t dark:border-border/30 border-light-border/30">
          <button
            onClick={() => setShowAll(!showAll)}
            className="
              w-full text-center py-2 text-[10px] font-medium cursor-pointer
              dark:text-text-tertiary dark:hover:text-accent
              text-light-text-tertiary hover:text-accent
              transition-colors
            "
          >
            {showAll ? '收起' : `顯示全部 ${rows.length} 行`}
          </button>
        </div>
      )}

      {/* Footer */}
      <div className="
        flex items-center justify-between
        px-4 py-2 border-t dark:border-border border-light-border
        text-[10px] dark:text-text-tertiary/50 text-light-text-tertiary/50
      ">
        <span>已加入 BOM 標記，Excel 可正確顯示中文</span>
        {editCount > 0 && (
          <span className="text-accent">
            已修改 {editCount} 個儲存格
          </span>
        )}
      </div>
    </motion.div>
  );
}
