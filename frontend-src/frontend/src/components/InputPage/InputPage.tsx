import { useState, useEffect } from 'react';
import NaturalLanguageInput from './NaturalLanguageInput';
import JsonEditor from './JsonEditor';
import GenerateButton from './GenerateButton';
import ErrorPanel from '../common/ErrorPanel';
import { api } from '../../api/rulesApi';
import type { InputMode, GenerationStep, ApiError, LlmProviderInfo } from '../../types';

// Provider 顯示名稱映射（v3.11.0）
const PROVIDER_DISPLAY: Record<string, string> = {
  claude: 'Claude',
  gemini: 'Gemini',
  openai: 'GPT',
  gpt: 'GPT',
  ollama: 'Ollama (本地)',
  offline: '離線模板',
};

// Provider → 對應環境變數名（tooltip 顯示給使用者）
const PROVIDER_ENV: Record<string, string> = {
  claude: 'CLAUDE_API_KEY',
  gemini: 'GEMINI_API_KEY',
  openai: 'OPENAI_API_KEY',
  gpt: 'OPENAI_API_KEY',
};

interface Props {
  loading: boolean;
  steps: GenerationStep[];
  error: ApiError | null;
  onGenerate: (description: string, mode: InputMode, jsonInput?: string, provider?: string, ruleType?: string) => void;
  onDismissError: () => void;
}

export default function InputPage({ loading, steps, error, onGenerate, onDismissError }: Props) {
  const [mode, setMode] = useState<InputMode>('natural');
  const [nlText, setNlText] = useState('');
  const [jsonText, setJsonText] = useState('');
  const [providers, setProviders] = useState<LlmProviderInfo[]>([]);
  const [selectedProvider, setSelectedProvider] = useState<string>('');
  const [ruleType, setRuleType] = useState<string>('auto');

  // 載入可用 providers
  useEffect(() => {
    api.getProviders().then(list => {
      setProviders(list);
      const def = list.find(p => p.isDefault && p.available);
      if (def) setSelectedProvider(def.name);
    });
  }, []);

  const canSubmit =
    mode === 'natural' ? nlText.trim().length > 0 : jsonText.trim().length > 0;

  const handleGenerate = () => {
    const rt = ruleType === 'auto' ? undefined : ruleType;
    if (mode === 'natural') {
      onGenerate(nlText, 'natural', undefined, selectedProvider, rt);
    } else {
      onGenerate(jsonText, 'json', jsonText, selectedProvider, rt);
    }
  };

  return (
    <div className="w-full max-w-2xl mx-auto space-y-5">
      {/* Hero — 品牌綠強調 */}
      <div className="text-center space-y-3 pt-14 pb-2">
        {/* 上方小 accent 線 — 品牌綠 */}
        <div className="flex items-center justify-center gap-2 mb-1">
          <span className="h-px w-6 bg-accent/60" />
          <span className="text-[10px] font-semibold tracking-[0.25em] text-accent uppercase">
            BUSINESS RULE ENGINE
          </span>
          <span className="h-px w-6 bg-accent/60" />
        </div>

        <h1 className="text-3xl sm:text-4xl font-bold tracking-tight dark:text-text-primary text-light-text-primary">
          業務規則<span className="text-accent">智能生成</span>
        </h1>
        <p className="text-sm dark:text-text-secondary text-light-text-secondary max-w-md mx-auto leading-relaxed">
          描述您的業務規則，AI 自動生成完整的決策表或決策樹，<br className="hidden sm:inline" />
          含覆蓋率驗證、衝突偵測、幻覺檢查與品質評分
        </p>
      </div>

      {/* Mode toggle */}
      <div className="flex items-center rounded-xl p-1
        dark:bg-surface-2 bg-light-surface-2
      ">
        <button
          onClick={() => setMode('natural')}
          className={`
            flex-1 py-2 text-xs font-medium rounded-lg transition-all duration-200 cursor-pointer
            ${mode === 'natural'
              ? 'dark:bg-surface-0 dark:text-accent bg-white text-accent shadow-sm'
              : 'dark:text-text-tertiary text-light-text-tertiary hover:opacity-80'
            }
          `}
        >
          文字描述
        </button>
        <button
          onClick={() => setMode('json')}
          className={`
            flex-1 py-2 text-xs font-medium rounded-lg transition-all duration-200 cursor-pointer
            ${mode === 'json'
              ? 'dark:bg-surface-0 dark:text-accent bg-white text-accent shadow-sm'
              : 'dark:text-text-tertiary text-light-text-tertiary hover:opacity-80'
            }
          `}
          title="若已有 RuleEnvelope 或規則資料，可直接貼上跳過 AI 生成"
        >
          直接貼上 RuleEnvelope
        </button>
      </div>

      {/* Input area */}
      <div className="rounded-2xl border p-5
        dark:bg-surface-1 dark:border-border/70
        bg-white border-light-border
        shadow-lg shadow-black/5 dark:shadow-black/20
      ">
        {mode === 'natural' ? (
          <NaturalLanguageInput value={nlText} onChange={setNlText} disabled={loading} />
        ) : (
          <JsonEditor value={jsonText} onChange={setJsonText} disabled={loading} />
        )}
      </div>

      {/* Error */}
      {error && (
        <ErrorPanel
          error={error}
          onRetry={handleGenerate}
          onDismiss={onDismissError}
        />
      )}

      {/* JSON 模式說明 */}
      {mode === 'json' && !loading && !error && (
        <div className="text-[11px] dark:text-text-tertiary text-light-text-tertiary text-center">
          直接貼上 RuleEnvelope 時不呼叫 AI 模型，僅做結構驗證與分析。
        </div>
      )}

      {/* 設定列：AI 模型 + 規則類型（< sm 堆疊） */}
      {mode === 'natural' && (
        <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
          {/* AI 模型 */}
          {providers.length > 0 && (
            <div className="flex items-center gap-2 flex-wrap">
              <span className="text-[11px] dark:text-text-tertiary text-light-text-tertiary shrink-0">
                AI 模型
              </span>
              <div className="flex gap-1.5 flex-wrap">
                {providers.map(p => {
                  const display = PROVIDER_DISPLAY[p.name] ?? p.name;
                  const envVar = PROVIDER_ENV[p.name];
                  const disabled = loading || !p.available;
                  const tooltip = !p.available
                    ? (envVar
                        ? '此 AI 模型尚未開通，請聯繫系統管理員'
                        : '此 AI 模型目前不可用')
                    : loading
                      ? '生成中無法切換'
                      : undefined;

                  return (
                    <button
                      key={p.name}
                      onClick={() => p.available && setSelectedProvider(p.name)}
                      disabled={disabled}
                      title={tooltip}
                      className={`inline-flex items-center gap-1 px-3 py-1 rounded-md text-[11px] font-medium transition-colors
                        ${selectedProvider === p.name && p.available
                          ? 'dark:bg-surface-3 dark:text-text-primary bg-light-surface-3 text-light-text-primary font-semibold cursor-pointer'
                          : p.available
                            ? 'dark:text-text-tertiary text-light-text-tertiary hover:dark:text-text-secondary hover:text-light-text-secondary cursor-pointer'
                            : 'dark:text-text-tertiary/50 text-light-text-tertiary/60 opacity-60 cursor-not-allowed'}
                        disabled:pointer-events-auto`}
                    >
                      {!p.available && (
                        <svg width="9" height="9" viewBox="0 0 10 10" fill="none" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round">
                          <rect x="2" y="4.5" width="6" height="4" rx="0.8" />
                          <path d="M3.5 4.5V3a1.5 1.5 0 013 0v1.5" />
                        </svg>
                      )}
                      {display}
                    </button>
                  );
                })}
              </div>
            </div>
          )}

          {/* 規則類型 */}
          <div className="flex items-center gap-2 flex-wrap">
            <span className="text-[11px] dark:text-text-tertiary text-light-text-tertiary shrink-0">
              規則類型
            </span>
            <div className="flex gap-1.5 flex-wrap">
              {[
                { value: 'auto', label: 'AI 推薦' },
                { value: 'DecisionTable', label: '決策表' },
                { value: 'DecisionTree', label: '決策樹' },
              ].map(opt => (
                <button
                  key={opt.value}
                  onClick={() => setRuleType(opt.value)}
                  disabled={loading}
                  title={loading ? '生成中無法切換' : undefined}
                  className={`px-3 py-1 rounded-md text-[11px] font-medium transition-colors
                    ${ruleType === opt.value
                      ? 'dark:bg-surface-3 dark:text-text-primary bg-light-surface-3 text-light-text-primary font-semibold cursor-pointer'
                      : 'dark:text-text-tertiary text-light-text-tertiary hover:dark:text-text-secondary hover:text-light-text-secondary cursor-pointer'}
                    disabled:opacity-50 disabled:cursor-not-allowed disabled:pointer-events-auto`}
                >
                  {opt.label}
                </button>
              ))}
            </div>
          </div>
        </div>
      )}
      <GenerateButton
        loading={loading}
        steps={steps}
        disabled={!canSubmit}
        onClick={handleGenerate}
        ruleType={ruleType === 'auto' ? undefined : ruleType}
        mode={mode}
      />

    </div>
  );
}
