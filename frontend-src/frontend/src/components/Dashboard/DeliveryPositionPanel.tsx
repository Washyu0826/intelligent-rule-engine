import type { RuleEnvelope } from '../../types';

interface Props {
  envelope?: RuleEnvelope;
  compact?: boolean;
}

const ITEMS = [
  {
    title: '業務規格輸入',
    body: '承接自然語言、既有表格或 JSON，把檢核範圍、條件、例外與訊息整理清楚。',
  },
  {
    title: 'RuleEnvelope 中介模型',
    body: '用 DecisionTable / DecisionTree 表達規則語意，保持 engine-neutral，不綁定特定執行引擎。',
  },
  {
    title: '治理與驗證',
    body: '檢查欄位、型別、衝突、覆蓋率與情境展開，讓業務能在交付前先審查。',
  },
  {
    title: '集團引擎 Adapter',
    body: '正式導入時再由 exporter / adapter 轉成集團規則引擎的欄位代碼、operator 與部署格式。',
  },
];

export default function DeliveryPositionPanel({ envelope, compact = false }: Props) {
  return (
    <section className="
      rounded-xl border overflow-hidden print:hidden
      dark:bg-surface-1 dark:border-border bg-white border-light-border
    ">
      <div className="
        px-4 py-3 border-b dark:border-border border-light-border
        dark:bg-surface-2/45 bg-light-surface-2/60
      ">
        <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-2">
          <div>
            <p className="text-xs font-bold dark:text-text-primary text-light-text-primary">
              業務規格到集團規則引擎的交付定位
            </p>
            <p className="text-[11px] dark:text-text-secondary text-light-text-secondary mt-1 leading-relaxed">
              本系統負責規則需求整理、結構化、驗證與交付，不直接宣稱產物等於集團規則引擎部署檔。
            </p>
          </div>
          {envelope && (
            <span className="inline-flex self-start sm:self-center px-2 py-1 rounded-md text-[10px] font-mono
              dark:bg-surface-3 dark:text-text-tertiary bg-light-surface-3 text-light-text-tertiary">
              {envelope.ruleType} · RuleEnvelope
            </span>
          )}
        </div>
      </div>

      {!compact && (
        <div className="grid grid-cols-1 sm:grid-cols-4 divide-y sm:divide-y-0 sm:divide-x dark:divide-border/50 divide-light-border/60">
          {ITEMS.map((item, index) => (
            <div key={item.title} className="p-4">
              <div className="w-6 h-6 rounded-md bg-accent/12 text-accent flex items-center justify-center text-[11px] font-bold mb-2">
                {index + 1}
              </div>
              <p className="text-xs font-semibold dark:text-text-primary text-light-text-primary">
                {item.title}
              </p>
              <p className="text-[11px] leading-relaxed mt-1 dark:text-text-secondary text-light-text-secondary">
                {item.body}
              </p>
            </div>
          ))}
        </div>
      )}
    </section>
  );
}
