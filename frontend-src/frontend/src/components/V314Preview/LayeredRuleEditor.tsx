import { useMemo, useState, type ReactNode } from 'react';
import type { GlossaryEntry, LayerAnnotation, LayeredRule } from './sampleData';

type LayerId = 'L1' | 'L2' | 'L3' | 'L4' | 'L5';

const LAYER_META: Record<LayerId, { name: string; audience: string; accent: string }> = {
  L1: { name: 'L1 · BA 摘要層', audience: 'BA / 稽核 / 法遵', accent: 'emerald' },
  L2: { name: 'L2 · 業務語意層', audience: 'BA + IT 對照', accent: 'blue' },
  L3: { name: 'L3 · 規則結構層', audience: '全員', accent: 'violet' },
  L4: { name: 'L4 · 執行表達層', audience: 'IT', accent: 'amber' },
  L5: { name: 'L5 · 整合對映層', audience: 'IT / 整合', accent: 'rose' },
};

const ACCENT_RING: Record<string, string> = {
  emerald: 'border-emerald-500/40 dark:bg-emerald-500/5 bg-emerald-50/60',
  blue: 'border-blue-500/40 dark:bg-blue-500/5 bg-blue-50/60',
  violet: 'border-violet-500/40 dark:bg-violet-500/5 bg-violet-50/60',
  amber: 'border-amber-500/40 dark:bg-amber-500/5 bg-amber-50/60',
  rose: 'border-rose-500/40 dark:bg-rose-500/5 bg-rose-50/60',
};

const ACCENT_BADGE: Record<string, string> = {
  emerald: 'dark:bg-emerald-500/20 dark:text-emerald-300 bg-emerald-100 text-emerald-700',
  blue: 'dark:bg-blue-500/20 dark:text-blue-300 bg-blue-100 text-blue-700',
  violet: 'dark:bg-violet-500/20 dark:text-violet-300 bg-violet-100 text-violet-700',
  amber: 'dark:bg-amber-500/20 dark:text-amber-300 bg-amber-100 text-amber-700',
  rose: 'dark:bg-rose-500/20 dark:text-rose-300 bg-rose-100 text-rose-700',
};

const ROLE_STYLE: Record<LayerAnnotation['role'], string> = {
  BA: 'dark:bg-emerald-500/15 dark:text-emerald-300 bg-emerald-100 text-emerald-700',
  IT: 'dark:bg-blue-500/15 dark:text-blue-300 bg-blue-100 text-blue-700',
  法遵: 'dark:bg-violet-500/15 dark:text-violet-300 bg-violet-100 text-violet-700',
  稽核: 'dark:bg-amber-500/15 dark:text-amber-300 bg-amber-100 text-amber-700',
};

interface Props {
  rule: LayeredRule;
  glossary: GlossaryEntry[];
  annotations: LayerAnnotation[];
  showAnnotations: boolean;
  highlightTermId: string | null;
  onClearHighlight: () => void;
}

export default function LayeredRuleEditor({
  rule,
  glossary,
  annotations,
  showAnnotations,
  highlightTermId,
  onClearHighlight,
}: Props) {
  // 預設展開 L1 + L2 + L3，L4 / L5 收合（漸進揭露）
  const [expanded, setExpanded] = useState<Record<LayerId, boolean>>({
    L1: true,
    L2: true,
    L3: true,
    L4: false,
    L5: false,
  });

  const annotationsByLayer = useMemo(() => {
    const m: Record<LayerId, LayerAnnotation[]> = { L1: [], L2: [], L3: [], L4: [], L5: [] };
    annotations.forEach((a) => m[a.layer].push(a));
    return m;
  }, [annotations]);

  const highlightTerm = useMemo(
    () => glossary.find((g) => g.id === highlightTermId) ?? null,
    [glossary, highlightTermId]
  );

  const toggle = (id: LayerId) =>
    setExpanded((prev) => ({ ...prev, [id]: !prev[id] }));

  return (
    <article
      className="
        rounded-xl border
        dark:bg-surface-1 dark:border-border bg-white border-light-border
        shadow-sm
      "
    >
      {/* Rule header */}
      <header className="px-4 py-3 border-b dark:border-border border-light-border">
        <div className="flex items-start justify-between gap-3 flex-wrap">
          <div className="min-w-0">
            <div className="flex items-center gap-2 mb-1">
              <span className="text-[10px] font-mono tabular-nums dark:text-text-tertiary text-light-text-tertiary">
                {rule.ruleId}
              </span>
              <span className={`px-1.5 py-0 rounded text-[10px] font-semibold ${reviewBadge(rule.audit.reviewStatus)}`}>
                {reviewLabel(rule.audit.reviewStatus)}
              </span>
              <span className="text-[10px] dark:text-text-tertiary text-light-text-tertiary">
                v{rule.audit.version} · 生效 {rule.audit.effectiveDate}
              </span>
            </div>
            <h2 className="text-sm font-bold dark:text-text-primary text-light-text-primary">
              {rule.narrative.summary}
            </h2>
            <div className="mt-1 flex flex-wrap gap-1">
              {rule.narrative.tags.map((t) => (
                <span
                  key={t}
                  className="
                    px-1.5 py-0 text-[10px] rounded
                    dark:bg-surface-2 dark:text-text-tertiary
                    bg-light-surface-2 text-light-text-tertiary
                  "
                >
                  #{t}
                </span>
              ))}
            </div>
          </div>
          <div className="text-[10px] text-right dark:text-text-tertiary text-light-text-tertiary shrink-0">
            <div>BA owner：{rule.audit.businessOwner}</div>
            <div>IT owner：{rule.audit.techOwner}</div>
            <div className="mt-0.5">審核：{rule.audit.reviewers.join(' · ')}</div>
          </div>
        </div>
      </header>

      {/* 字彙表 highlight banner */}
      {highlightTerm && (
        <div
          className="
            px-4 py-2 border-b
            dark:bg-accent/10 dark:border-accent/30
            bg-accent/5 border-accent/40
            flex items-center justify-between gap-3 animate-fade-in-up
          "
        >
          <div className="text-[11px] dark:text-text-primary text-light-text-primary">
            <span className="font-semibold">已聚焦字彙：</span>
            <span className="ml-1">{highlightTerm.zh_TW}</span>
            <span className="dark:text-text-tertiary text-light-text-tertiary ml-2">
              （{highlightTerm.en}）{highlightTerm.definition}
            </span>
          </div>
          <button
            onClick={onClearHighlight}
            className="text-[11px] cursor-pointer dark:text-text-tertiary text-light-text-tertiary hover:dark:text-text-primary hover:text-light-text-primary"
          >
            清除 ✕
          </button>
        </div>
      )}

      {/* 5 Layers */}
      <div className="divide-y dark:divide-border/50 divide-light-border/60">
        {(Object.keys(LAYER_META) as LayerId[]).map((id) => (
          <LayerSection
            key={id}
            id={id}
            isExpanded={expanded[id]}
            onToggle={() => toggle(id)}
            annotationCount={annotationsByLayer[id].length}
          >
            {expanded[id] && (
              <>
                {renderLayerContent(id, rule, highlightTermId)}
                {showAnnotations && annotationsByLayer[id].length > 0 && (
                  <AnnotationList annotations={annotationsByLayer[id]} />
                )}
              </>
            )}
          </LayerSection>
        ))}
      </div>

      {/* Footer：regulatory ref + 簽核狀態 */}
      <footer className="px-4 py-2.5 border-t dark:border-border border-light-border flex items-center justify-between flex-wrap gap-2">
        <div className="text-[10px] dark:text-text-tertiary text-light-text-tertiary">
          法規依據：<code className="dark:bg-surface-2 bg-light-surface-2 px-1 py-0 rounded">{rule.audit.regulatoryRef}</code>
        </div>
        <div className="flex gap-1.5">
          <button className="
            px-2 py-1 text-[10px] rounded font-medium cursor-pointer transition-colors
            dark:bg-surface-2 dark:text-text-secondary dark:hover:bg-surface-3
            bg-light-surface-2 text-light-text-secondary hover:bg-light-surface-3
          ">
            檢視歷史版本
          </button>
          <button className="
            px-2 py-1 text-[10px] rounded font-medium cursor-pointer transition-colors
            bg-accent text-white hover:bg-accent-dim
          ">
            送出簽核
          </button>
        </div>
      </footer>
    </article>
  );
}

// ────────────────────────────────────────────────────────
// 可折疊單一層
// ────────────────────────────────────────────────────────

function LayerSection({
  id,
  isExpanded,
  onToggle,
  annotationCount,
  children,
}: {
  id: LayerId;
  isExpanded: boolean;
  onToggle: () => void;
  annotationCount: number;
  children: ReactNode;
}) {
  const meta = LAYER_META[id];
  return (
    <section className={`border-l-4 ${ACCENT_RING[meta.accent]}`}>
      <button
        onClick={onToggle}
        className="
          w-full text-left px-4 py-2.5 flex items-center justify-between
          transition-colors cursor-pointer
          hover:dark:bg-surface-2/50 hover:bg-light-surface-2/60
        "
      >
        <div className="flex items-center gap-2 min-w-0">
          <span className="text-xs dark:text-text-tertiary text-light-text-tertiary">
            {isExpanded ? '▼' : '▶'}
          </span>
          <span className="text-xs font-bold dark:text-text-primary text-light-text-primary">
            {meta.name}
          </span>
          <span className={`px-1.5 py-0 rounded text-[9px] font-medium ${ACCENT_BADGE[meta.accent]}`}>
            給 {meta.audience}
          </span>
        </div>
        {annotationCount > 0 && (
          <span className="
            px-1.5 py-0 rounded-full text-[9px] font-bold shrink-0
            dark:bg-warning/20 dark:text-warning
            bg-warning/15 text-warning
          ">
            💬 {annotationCount}
          </span>
        )}
      </button>
      <div className="px-4 pb-3">{children}</div>
    </section>
  );
}

// ────────────────────────────────────────────────────────
// 每層的內容渲染
// ────────────────────────────────────────────────────────

function renderLayerContent(id: LayerId, rule: LayeredRule, highlightTermId: string | null) {
  switch (id) {
    case 'L1':
      return (
        <div className="space-y-2 text-[12.5px] dark:text-text-primary text-light-text-primary leading-relaxed">
          <div>
            <span className="dark:text-text-tertiary text-light-text-tertiary text-[10px] uppercase tracking-wider">敘述</span>
            <div className="mt-0.5">{rule.narrative.summary}</div>
          </div>
          <div>
            <span className="dark:text-text-tertiary text-light-text-tertiary text-[10px] uppercase tracking-wider">分類標籤</span>
            <div className="mt-0.5 flex gap-1">
              {rule.narrative.tags.map((t) => <Tag key={t}>{t}</Tag>)}
            </div>
          </div>
        </div>
      );

    case 'L2':
      return (
        <div className="space-y-2 text-[12px]">
          <div>
            <span className="dark:text-text-tertiary text-light-text-tertiary text-[10px] uppercase tracking-wider">適用範圍 (scope)</span>
            <div className="mt-0.5 dark:text-text-secondary text-light-text-secondary italic">
              {rule.businessSemantic.scope ?? '所有案件（無 scope 守門）'}
            </div>
          </div>
          <div>
            <span className="dark:text-text-tertiary text-light-text-tertiary text-[10px] uppercase tracking-wider">條件</span>
            <ul className="mt-1 space-y-1">
              {rule.businessSemantic.conditions.map((c, i) => (
                <li key={i} className="
                  px-2 py-1.5 rounded
                  dark:bg-surface-2 bg-light-surface-2
                  text-[12px] dark:text-text-primary text-light-text-primary
                ">
                  <BusinessTerm name={c.fieldTerm} highlight={highlightTermId} />
                  <span className="mx-1.5 dark:text-text-tertiary text-light-text-tertiary">{c.operatorTerm}</span>
                  <BusinessTerm name={c.valueTerm} highlight={highlightTermId} />
                </li>
              ))}
            </ul>
          </div>
          <div>
            <span className="dark:text-text-tertiary text-light-text-tertiary text-[10px] uppercase tracking-wider">結果</span>
            <div className="mt-0.5 dark:text-text-primary text-light-text-primary px-2 py-1.5 rounded dark:bg-danger/10 bg-danger/5 text-[12px]">
              ⚠ {rule.businessSemantic.resultTerm}
            </div>
          </div>
        </div>
      );

    case 'L3':
      return (
        <div className="space-y-2 text-[12px]">
          <div className="grid grid-cols-2 gap-2">
            <KeyValue k="規則型態" v={rule.ruleStructure.ruleType} />
            <KeyValue k="hitPolicy" v={rule.ruleStructure.hitPolicy} />
            <KeyValue k="severity" v={rule.ruleStructure.severity} />
            <KeyValue k="輸出欄位" v={`${rule.ruleStructure.output.name} (${rule.ruleStructure.output.typeRef})`} />
          </div>
          <div>
            <span className="dark:text-text-tertiary text-light-text-tertiary text-[10px] uppercase tracking-wider">輸入欄位 ({rule.ruleStructure.inputs.length})</span>
            <ul className="mt-1 space-y-0.5">
              {rule.ruleStructure.inputs.map((inp) => (
                <li key={inp.name} className="
                  px-2 py-1 rounded text-[11px]
                  dark:bg-surface-2 bg-light-surface-2
                  flex items-center justify-between gap-2
                ">
                  <code className="dark:text-accent text-accent">{inp.name}</code>
                  <span className="dark:text-text-tertiary text-light-text-tertiary">
                    {inp.typeRef}
                    {inp.allowedValues && <> · [{inp.allowedValues.join(', ')}]</>}
                  </span>
                </li>
              ))}
            </ul>
          </div>
          <div>
            <span className="dark:text-text-tertiary text-light-text-tertiary text-[10px] uppercase tracking-wider">errorMessage</span>
            <div className="mt-0.5 px-2 py-1.5 rounded dark:bg-danger/10 bg-danger/5 text-[12px]">
              {rule.ruleStructure.errorMessage}
            </div>
          </div>
        </div>
      );

    case 'L4':
      return (
        <div className="space-y-1 text-[12px] font-mono">
          {rule.executionLayer.map((c, i) => (
            <div key={i} className="
              px-2 py-1.5 rounded
              dark:bg-surface-2 bg-light-surface-2
              flex items-center gap-2 flex-wrap
            ">
              <code className="dark:text-accent text-accent">{c.field}</code>
              <span className="dark:text-text-tertiary text-light-text-tertiary">{c.operator}</span>
              <code className="dark:text-text-primary text-light-text-primary">
                {typeof c.value === 'boolean' ? String(c.value) : `"${c.value}"`}
              </code>
            </div>
          ))}
          <div className="text-[10px] dark:text-text-tertiary text-light-text-tertiary mt-2 italic">
            註：top-level conditions 為 AND 連接（v3.13 既有語意）
          </div>
        </div>
      );

    case 'L5':
      return (
        <div className="space-y-1.5 text-[11px]">
          {Object.entries(rule.integration.fieldMappings).map(([field, m]) => (
            <div key={field} className="
              px-2.5 py-1.5 rounded
              dark:bg-surface-2 bg-light-surface-2
              border-l-2 dark:border-rose-500/40 border-rose-500/40
            ">
              <div className="flex items-center gap-1.5 flex-wrap">
                <code className="dark:text-accent text-accent">{field}</code>
                <span className="dark:text-text-tertiary text-light-text-tertiary">→</span>
                <code className="dark:text-text-primary text-light-text-primary">{m.externalSystem}.{m.externalField}</code>
              </div>
              {m.transform && (
                <div className="mt-0.5 dark:text-text-tertiary text-light-text-tertiary">
                  transform: <code>{m.transform}</code>
                </div>
              )}
            </div>
          ))}
        </div>
      );
  }
}

// ────────────────────────────────────────────────────────
// 小元件
// ────────────────────────────────────────────────────────

function KeyValue({ k, v }: { k: string; v: string }) {
  return (
    <div className="px-2 py-1 rounded dark:bg-surface-2 bg-light-surface-2">
      <div className="text-[9px] dark:text-text-tertiary text-light-text-tertiary uppercase tracking-wider">{k}</div>
      <div className="text-[11px] font-medium dark:text-text-primary text-light-text-primary">{v}</div>
    </div>
  );
}

function Tag({ children }: { children: ReactNode }) {
  return (
    <span className="
      px-1.5 py-0 text-[10px] rounded
      dark:bg-surface-2 dark:text-text-tertiary
      bg-light-surface-2 text-light-text-tertiary
    ">
      #{children}
    </span>
  );
}

function BusinessTerm({ name, highlight }: { name: string; highlight: string | null }) {
  // 簡單把字彙表 zh_TW 跟 condition 文字做模糊匹配，命中時加底色
  const isHit = !!highlight && nameMatchesTermId(name, highlight);
  return (
    <span
      className={
        isHit
          ? 'px-1 rounded dark:bg-accent/20 bg-accent/15 dark:text-accent text-accent font-semibold'
          : 'dark:text-text-primary text-light-text-primary'
      }
    >
      {name}
    </span>
  );
}

function nameMatchesTermId(name: string, termId: string) {
  const map: Record<string, string[]> = {
    insured: ['被保人', '被保險人'],
    proposer: ['要保人'],
    'roc-id': ['身分證'],
    nationality: ['國籍'],
    'sum-insured': ['保額'],
    premium: ['保費'],
  };
  return (map[termId] || []).some((kw) => name.includes(kw));
}

function AnnotationList({ annotations }: { annotations: LayerAnnotation[] }) {
  return (
    <div className="mt-3 pt-2.5 border-t dark:border-border/50 border-light-border/60 space-y-1.5">
      <div className="text-[9px] uppercase tracking-wider dark:text-text-tertiary text-light-text-tertiary">
        Inline Annotations ({annotations.length})
      </div>
      {annotations.map((a) => (
        <div
          key={a.id}
          className={`
            px-2.5 py-1.5 rounded text-[11px]
            dark:bg-surface-2/60 bg-light-surface-2/60
            border-l-2 ${a.resolved ? 'dark:border-success/40 border-success/40 opacity-70' : 'dark:border-warning/60 border-warning/60'}
          `}
        >
          <div className="flex items-center gap-1.5 mb-0.5 flex-wrap">
            <span className={`px-1 py-0 rounded text-[9px] font-bold ${ROLE_STYLE[a.role]}`}>
              {a.role}
            </span>
            <span className="dark:text-text-secondary text-light-text-secondary text-[10px]">
              {a.author}
            </span>
            <span className="dark:text-text-tertiary text-light-text-tertiary text-[10px] ml-auto">
              {a.createdAt}
            </span>
            {a.resolved && (
              <span className="text-[9px] dark:text-success text-success font-bold">✓ resolved</span>
            )}
          </div>
          <div className="dark:text-text-primary text-light-text-primary leading-relaxed">
            {a.content}
          </div>
        </div>
      ))}
    </div>
  );
}

function reviewBadge(status: LayeredRule['audit']['reviewStatus']) {
  switch (status) {
    case 'draft': return 'dark:bg-surface-3 dark:text-text-secondary bg-light-surface-3 text-light-text-secondary';
    case 'in_review': return 'dark:bg-warning/20 dark:text-warning bg-warning/15 text-warning';
    case 'approved': return 'dark:bg-success/20 dark:text-success bg-success/15 text-success';
    case 'deployed': return 'dark:bg-accent/20 dark:text-accent bg-accent/15 text-accent';
  }
}

function reviewLabel(status: LayeredRule['audit']['reviewStatus']) {
  switch (status) {
    case 'draft': return '草稿';
    case 'in_review': return '審核中';
    case 'approved': return '已核准';
    case 'deployed': return '已部署';
  }
}
