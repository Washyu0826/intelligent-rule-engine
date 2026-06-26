import { useState, useCallback } from 'react';
import { api } from '../api/rulesApi';
import type {
  GenerationResult,
  GenerationStep,
  ApiError,
  RuleEnvelope,
  RecommendResponse,
  ValidateResponse,
  AnalyzeResponse,
  GroundingReport,
  ConfidenceReport,
} from '../types';
// Note: GenerateResponse is handled internally by api.generate()

const INITIAL_STEPS: GenerationStep[] = [
  { id: 'recommend', label: '理解業務規格語意', status: 'pending' },
  { id: 'generate', label: '識別條件與結果欄位', status: 'pending' },
  { id: 'matrix', label: '建立 RuleEnvelope 中介模型', status: 'pending' },
  { id: 'validate', label: '檢查規則是否可審查', status: 'pending' },
  { id: 'analyze', label: '分析情境覆蓋與交付風險', status: 'pending' },
];

export function useRuleGeneration() {
  const [loading, setLoading] = useState(false);
  const [steps, setSteps] = useState<GenerationStep[]>(INITIAL_STEPS);
  const [result, setResult] = useState<GenerationResult | null>(null);
  const [error, setError] = useState<ApiError | null>(null);

  const updateStep = (id: string, status: GenerationStep['status']) => {
    setSteps((prev) =>
      prev.map((s) => (s.id === id ? { ...s, status } : s))
    );
  };

  const reset = useCallback(() => {
    setLoading(false);
    setSteps(INITIAL_STEPS);
    setResult(null);
    setError(null);
  }, []);

  const generate = useCallback(
    async (description: string, mode: 'natural' | 'json', jsonInput?: string, provider?: string, forceRuleType?: string) => {
      reset();
      setLoading(true);

      // 視覺停頓，讓使用者看到 5 格進度條一格一格跑過（JSON 模式的步驟太快會被 React 合併成一次 render）
      const tick = (ms = 280) => new Promise<void>(r => setTimeout(r, ms));

      try {
        // ── Step 1: Recommend ──
        let recommend: RecommendResponse | undefined;
        updateStep('recommend', 'active');

        if (mode === 'natural') {
          recommend = await api.recommend(description);
        } else {
          await tick();
        }
        updateStep('recommend', 'done');

        // ── Step 2-3: Generate ──
        updateStep('generate', 'active');
        const ruleType = forceRuleType || recommend?.recommendedRuleType || 'DecisionTable';

        let envelope: RuleEnvelope;
        let validate: ValidateResponse | undefined;
        let analyze: AnalyzeResponse | undefined;
        let durationMs: number | undefined;
        let grounding: GroundingReport | undefined;
        let confidence: ConfidenceReport | undefined;

        if (mode === 'json' && jsonInput) {
          // JSON mode: parse and send to validate + analyze（逐步更新讓 5 格進度條跑滿）
          await tick(220);
          let parsed;
          try {
            parsed = JSON.parse(jsonInput);
          } catch (parseErr) {
            throw {
              message: `JSON 格式無效: ${(parseErr as Error).message}`,
              suggestion: '請檢查大括號、引號與逗號是否完整',
              retryable: false,
            } as ApiError;
          }
          envelope = {
            ruleType: parsed.ruleType || 'DecisionTable',
            reason: parsed.reason || 'JSON 直接輸入模式',
            evaluation: parsed.evaluation || {
              completeness: 'UNKNOWN',
              totalScenarios: 0,
              coverageRate: 0,
              conflictDetection: 'UNKNOWN',
              recommendedStrategy: 'FIRST',
            },
            rule: parsed.rule || parsed,
            schemaVersion: parsed.schemaVersion || 'direct-input',
            promptVersion: parsed.promptVersion || 'n/a',
          };
          updateStep('generate', 'done');

          updateStep('matrix', 'active');
          await tick(220);
          updateStep('matrix', 'done');

          // validate 與 analyze 平行打、但 UI 逐步顯示，讓使用者看到第 4、5 格依序亮
          const envelopeJson = envelope as unknown as Record<string, unknown>;
          updateStep('validate', 'active');
          const validatePromise = api.validate(envelopeJson, envelope.ruleType);
          const analyzePromise = api.analyze(envelopeJson, envelope.ruleType);

          try {
            validate = await validatePromise;
            updateStep('validate', 'done');
          } catch {
            updateStep('validate', 'error');
          }

          updateStep('analyze', 'active');
          await tick(180);
          try {
            analyze = await analyzePromise;
            updateStep('analyze', 'done');
          } catch {
            updateStep('analyze', 'error');
          }
        } else {
          // Natural language mode: SSE streaming with step-by-step progress
          try {
            const response = await api.generateStream(
              { description, ruleType, provider },
              (step, status) => {
                // Map SSE step events to UI steps
                const stepMap: Record<string, string> = {
                  recommend: 'recommend',
                  generate: 'generate',
                  validate: 'validate',
                  analyze: 'analyze',
                };
                const uiStep = stepMap[step];
                if (uiStep && (status === 'active' || status === 'done' || status === 'error')) {
                  updateStep(uiStep, status as GenerationStep['status']);
                  if (step === 'generate' && status === 'done') {
                    updateStep('matrix', 'done');
                  }
                }
              },
            );
            envelope = response.envelope;
            validate = response.validation;
            analyze = response.analysis;
            durationMs = response.durationMs;
            grounding = response.groundingCheck;
            confidence = response.confidence;
          } catch {
            // Fallback to non-streaming API
            const response = await api.generate({ description, ruleType, provider });
            envelope = response.envelope;
            validate = response.validation;
            analyze = response.analysis;
            durationMs = response.durationMs;
            grounding = response.groundingCheck;
            confidence = response.confidence;
            updateStep('generate', 'done');
            updateStep('matrix', 'done');
            updateStep('validate', validate ? 'done' : 'error');
            updateStep('analyze', analyze ? 'done' : 'error');
          }
        }

        const generationResult: GenerationResult = {
          recommend,
          generate: envelope,
          validate,
          durationMs,
          analyze,
          originalDescription: description,
          grounding,
          confidence,
        };

        setResult(generationResult);
        setLoading(false);
        return generationResult;
      } catch (err: unknown) {
        const apiErr: ApiError =
          err && typeof err === 'object' && 'message' in err
            ? (err as ApiError)
            : {
                message: String(err),
                suggestion: '請確認後端服務是否正常運行',
                retryable: true,
              };
        setError(apiErr);
        setLoading(false);

        // Mark remaining steps as error
        setSteps((prev) =>
          prev.map((s) =>
            s.status === 'active' || s.status === 'pending'
              ? { ...s, status: 'error' as const }
              : s
          )
        );
        throw apiErr;
      }
    },
    [reset]
  );

  return { loading, steps, result, error, generate, reset };
}
