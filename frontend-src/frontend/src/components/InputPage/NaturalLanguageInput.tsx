import { useState, useEffect, useRef } from 'react';
import { useState as useStateLocal } from 'react';
import { DEMO_SCENARIOS, SCENARIO_CATEGORIES } from '../../constants/scenarios';
import SuggestionPanel from './SuggestionPanel';
import PreflightBanner from './PreflightBanner';
import type { SuggestResponse, PreflightReport } from '../../types';
import { api } from '../../api/rulesApi';

interface Props {
  value: string;
  onChange: (val: string) => void;
  disabled?: boolean;
}

export default function NaturalLanguageInput({ value, onChange, disabled }: Props) {
  const [suggestion, setSuggestion] = useState<SuggestResponse | null>(null);
  const [preflight, setPreflight] = useState<PreflightReport | null>(null);
  const [suggestLoading, setSuggestLoading] = useState(false);
  const debounceRef = useRef<ReturnType<typeof setTimeout>>(null);

  // Debounced suggest + preflight call（同一 debounce，並行兩個純符號端點）
  useEffect(() => {
    if (debounceRef.current) clearTimeout(debounceRef.current);
    if (!value || value.trim().length < 10) {
      setSuggestion(null);
      setPreflight(null);
      return;
    }
    setSuggestLoading(true);
    debounceRef.current = setTimeout(async () => {
      try {
        const [sug, pre] = await Promise.all([
          api.suggest(value).catch(() => null),
          api.preflight(value).catch(() => null),
        ]);
        setSuggestion(sug);
        setPreflight(pre);
      } finally {
        setSuggestLoading(false);
      }
    }, 800);
    return () => { if (debounceRef.current) clearTimeout(debounceRef.current); };
  }, [value]);

  const handleInsertDimension = (text: string) => {
    onChange(value + text);
  };
  return (
    <div className="space-y-4">
      {/* 撰寫指引 */}
      <details className="rounded-lg border dark:bg-surface-1 dark:border-border bg-white border-light-border">
        <summary className="px-3 py-2 text-xs font-medium cursor-pointer
          dark:text-text-secondary text-light-text-secondary select-none">
          撰寫小撇步（點擊展開）
        </summary>
        <div className="px-3 pb-3 text-[11px] leading-relaxed space-y-2
          dark:text-text-tertiary text-light-text-tertiary">
          <p className="text-emerald-600 dark:text-emerald-400">
            ✓ 推薦寫法：<br />
            「若被保人 ID 為中華民國身分證格式且國籍別不為 TW，拋出錯誤 1.1；若要保人 ID 不為身分證格式且國籍別為 TW，拋出錯誤 1.4。」
          </p>
          <p className="text-red-500 dark:text-red-400">
            ✗ 建議避免：<br />
            「幫我檢查資料合理性」 — 沒有列出欄位、條件與錯誤訊息，AI 只能猜<br />
            「通路不對就擋」 — 沒寫哪些通路、擋下時要回哪個錯誤碼
          </p>
          <p>
            <span className="font-semibold">關鍵三點：</span>
            <span className="block mt-1">① 列出檢核範圍與條件欄位（通路、ID 格式、國籍、繳費管道…）</span>
            <span className="block">② 明確寫出例外與值域（TW / NON_TW、保代 / 直效 / 特約）</span>
            <span className="block">③ 寫出每條檢核命中後的錯誤碼、訊息與是否可多重命中</span>
          </p>
        </div>
      </details>

      {/* Textarea */}
      <div className="relative group">
        <label htmlFor="rule-description" className="sr-only">業務規則描述</label>
        <textarea
          id="rule-description"
          value={value}
          onChange={(e) => onChange(e.target.value)}
          disabled={disabled}
          aria-label="業務規則描述"
          aria-describedby="rule-char-count"
          placeholder="用業務對話方式描述規格，例如：若被保人 ID 是身分證格式但國籍不是 TW，要回錯誤 1.1..."
          rows={5}
          className="
            w-full px-4 py-3 rounded-xl text-sm leading-relaxed resize-none
            font-sans placeholder:opacity-40
            outline-none transition-all duration-200
            dark:bg-surface-2 dark:text-text-primary dark:border-border
            dark:focus:border-accent/50 dark:focus:ring-1 dark:focus:ring-accent/20
            bg-light-surface-2 text-light-text-primary border-light-border
            focus:border-accent/50 focus:ring-1 focus:ring-accent/20
            border
            disabled:opacity-50 disabled:cursor-not-allowed
          "
        />
        <div id="rule-char-count" className="absolute bottom-2 right-3 text-[10px] tabular-nums
          dark:text-text-tertiary text-light-text-tertiary" aria-live="polite">
          {value.length} 字
        </div>
      </div>

      {/* Pre-flight 紅黃綠燈（生成前偵測，0 token） */}
      <PreflightBanner report={preflight} loading={suggestLoading} />

      {/* Suggestion Panel */}
      <SuggestionPanel
        suggestion={suggestion}
        loading={suggestLoading}
        onInsertDimension={handleInsertDimension}
      />

      {/* 規則模板庫 */}
      <TemplateLibrary onChange={onChange} disabled={disabled} />
    </div>
  );
}

// ── 規則模板庫子組件 ──
function TemplateLibrary({ onChange, disabled }: { onChange: (val: string) => void; disabled?: boolean }) {
  const [activeCategory, setActiveCategory] = useStateLocal<string>('insurance');
  const filtered = DEMO_SCENARIOS.filter(s => s.category === activeCategory);

  return (
    <div className="space-y-3">
      <p className="text-sm font-bold dark:text-text-primary text-light-text-primary">
        快速範例
      </p>
      {/* 分類 tab */}
      <div className="flex gap-2">
        {SCENARIO_CATEGORIES.map(cat => (
          <button
            key={cat.id}
            onClick={() => setActiveCategory(cat.id)}
            className={`px-3 py-1 rounded-md text-xs transition-colors cursor-pointer
              ${activeCategory === cat.id
                ? 'font-bold dark:bg-surface-3 dark:text-text-primary bg-light-surface-3 text-light-text-primary'
                : 'font-medium dark:text-text-tertiary text-light-text-tertiary hover:dark:text-text-secondary hover:text-light-text-secondary'}`}
          >
            {cat.label}
          </button>
        ))}
      </div>
      {/* 場景按鈕 */}
      <div className="flex flex-wrap gap-2">
        {filtered.map((s) => (
          <button
            key={s.id}
            onClick={() => onChange(s.description)}
            disabled={disabled}
            className="px-3 py-1.5 rounded-lg text-xs font-semibold
              transition-all duration-200 cursor-pointer border
              dark:bg-surface-2 dark:text-text-secondary dark:hover:text-text-primary dark:hover:bg-surface-3 dark:border-border
              bg-light-surface-2 text-light-text-secondary hover:text-light-text-primary hover:bg-light-surface-3 border-light-border
              disabled:opacity-50 disabled:cursor-not-allowed"
            title={s.description}
          >
            {s.label}{s.ruleType === 'DecisionTree' ? ' (樹)' : ''}
          </button>
        ))}
      </div>
    </div>
  );
}
