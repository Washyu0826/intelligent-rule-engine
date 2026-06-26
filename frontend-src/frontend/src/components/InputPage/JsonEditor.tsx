import { useState } from 'react';

interface Props {
  value: string;
  onChange: (val: string) => void;
  disabled?: boolean;
}

const SAMPLE_JSON = JSON.stringify(
  {
    ruleType: 'DecisionTable',
    description: '保險費率規則',
    rule: {
      inputs: [
        { id: 'age', label: '年齡', typeRef: 'INTEGER' },
        { id: 'smoker', label: '吸菸', typeRef: 'BOOLEAN' },
      ],
      outputs: [
        { id: 'rate', label: '費率等級', typeRef: 'ENUM', allowedValues: ['A', 'B', 'C', 'D'] },
      ],
      rules: [],
    },
  },
  null,
  2
);

export default function JsonEditor({ value, onChange, disabled }: Props) {
  const [parseError, setParseError] = useState<string | null>(null);

  const handleChange = (raw: string) => {
    onChange(raw);
    if (!raw.trim()) {
      setParseError(null);
      return;
    }
    try {
      JSON.parse(raw);
      setParseError(null);
    } catch (e) {
      setParseError((e as Error).message);
    }
  };

  return (
    <div className="space-y-3">
      <div className="relative">
        <textarea
          value={value}
          onChange={(e) => handleChange(e.target.value)}
          disabled={disabled}
          placeholder="貼上規則資料（JSON 格式），或點下方「載入示範資料」"
          rows={12}
          spellCheck={false}
          className="
            w-full px-4 py-3 rounded-xl text-xs leading-relaxed resize-y
            font-mono placeholder:opacity-40
            outline-none transition-all duration-200
            dark:bg-surface-2 dark:text-text-primary dark:border-border
            dark:focus:border-accent/50 dark:focus:ring-1 dark:focus:ring-accent/20
            bg-light-surface-2 text-light-text-primary border-light-border
            focus:border-accent/50 focus:ring-1 focus:ring-accent/20
            border
            disabled:opacity-50 disabled:cursor-not-allowed
          "
        />
        {/* Line count + status */}
        <div className="absolute bottom-2 right-3 flex items-center gap-2">
          {value.trim() && (
            <span className={`text-[10px] font-medium ${parseError ? 'text-danger' : 'text-success'}`}>
              {parseError ? '✕ 格式錯誤' : '✓ 格式正確'}
            </span>
          )}
          <span className="text-[10px] tabular-nums dark:text-text-tertiary text-light-text-tertiary">
            {value.split('\n').length} 行
          </span>
        </div>
      </div>

      {/* Parse error */}
      {parseError && (
        <p className="text-xs text-danger font-mono px-1 truncate">
          {parseError}
        </p>
      )}

      {/* Load sample button */}
      <button
        onClick={() => handleChange(SAMPLE_JSON)}
        disabled={disabled}
        className="
          text-xs px-3 py-1.5 rounded-lg cursor-pointer
          transition-colors duration-200
          dark:text-text-tertiary dark:hover:text-accent dark:bg-surface-2 dark:hover:bg-accent-muted
          text-light-text-tertiary hover:text-accent bg-light-surface-2 hover:bg-accent/5
        "
      >
        載入示範資料
      </button>
    </div>
  );
}
