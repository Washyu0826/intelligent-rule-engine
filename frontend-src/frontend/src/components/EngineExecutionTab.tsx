import { useCallback, useEffect, useMemo, useState } from 'react';
import { api } from '../api/rulesApi';
import type { GroupTree, GroupTreeOutput, ExecutionResult, RuleEnvelope } from '../types';
import GroupTreeView from './GroupTreeView';
import JsonViewer from './Export/JsonViewer';

interface EngineExecutionTabProps {
  envelope: RuleEnvelope;
}

interface EngineField {
  name: string;
  typeRef: string;
  allowedValues?: unknown[];
  externalCodes?: Record<string, string>;
  nullable?: boolean;
  unit?: string;
}

interface SelectOption {
  value: string;
  label: string;
}

interface EditorEnumCodeItem {
  code: string;
  value: string;
}

interface EditorMetadata {
  index: number;
  label: string;
  desc: string;
  columnName: string;
  dataType: string;
  mandatory: boolean;
  resultValue: unknown;
  enumCodeItems: EditorEnumCodeItem[];
}

interface EditorNode {
  id: string;
  type: 'custom';
  position: GroupTree['nodes'][number]['position'];
  data: {
    columnType: '0' | '1';
    metadataList: EditorMetadata[];
    ruleIndex: null;
  };
}

interface EditorEdge {
  id: string;
  source: string;
  target: string;
  data: {
    columnLabel: string;
    columnName: string;
    expressionType: string;
    entryText: string;
  };
}

interface DecisionTreeEditorJson {
  nodes: EditorNode[];
  edges: EditorEdge[];
}

function getInputs(envelope: RuleEnvelope): EngineField[] {
  const rule = envelope.rule as Record<string, unknown> | undefined;
  return Array.isArray(rule?.inputs) ? (rule.inputs as EngineField[]) : [];
}

function getOutputs(envelope: RuleEnvelope): EngineField[] {
  const rule = envelope.rule as Record<string, unknown> | undefined;
  return Array.isArray(rule?.outputs) ? (rule.outputs as EngineField[]) : [];
}

function getOptions(field: EngineField): SelectOption[] {
  const options: SelectOption[] = [];
  for (const raw of field.allowedValues ?? []) {
    if (typeof raw === 'string' || typeof raw === 'number' || typeof raw === 'boolean') {
      const value = String(raw);
      const label = field.externalCodes?.[value] ? `${field.externalCodes[value]} (${value})` : value;
      options.push({ value, label });
      continue;
    }

    if (raw && typeof raw === 'object') {
      for (const [key, value] of Object.entries(raw as Record<string, unknown>)) {
        options.push({ value: key, label: `${String(value)} (${key})` });
      }
    }
  }
  return options;
}

function coerceInputValue(field: EngineField, value: string): unknown {
  if (value === '') return '';
  if (field.typeRef === 'INTEGER') return Number.parseInt(value, 10);
  if (field.typeRef === 'DECIMAL') return Number.parseFloat(value);
  if (field.typeRef === 'BOOLEAN') return value === 'true';
  return value;
}

function saveBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
}

function saveJson(payload: unknown, filename: string) {
  saveBlob(new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' }), filename);
}

function saveTreeJson(tree: GroupTree, filename = `group-tree-${Date.now()}.json`) {
  saveJson(tree, filename);
}

function fieldMap(fields: EngineField[]): Map<string, EngineField> {
  return new Map(fields.map((field) => [field.name, field]));
}

function toEditorDataType(typeRef?: string): string {
  switch ((typeRef ?? '').toUpperCase()) {
    case 'ENUM':
      return '7';
    case 'INTEGER':
    case 'DECIMAL':
      return '6';
    case 'STRING':
    case 'BOOLEAN':
    case 'DATE':
    case 'TIMESTAMP':
    case 'VARIABLE':
    default:
      return '1';
  }
}

function toEditorExpressionType(operator?: string): string {
  switch ((operator ?? '').toLowerCase()) {
    case 'equals':
      return '1';
    case 'notequals':
      return '2';
    case 'greaterthan':
    case 'greaterthanorequal':
      return '3';
    case 'lessthan':
    case 'lessthanorequal':
      return '5';
    case 'between':
      return '10';
    case 'in':
    case 'and':
    default:
      return '1';
  }
}

function enumCodeItems(field?: EngineField): EditorEnumCodeItem[] {
  const items: EditorEnumCodeItem[] = [];
  for (const raw of field?.allowedValues ?? []) {
    if (typeof raw === 'string' || typeof raw === 'number' || typeof raw === 'boolean') {
      const code = String(raw);
      items.push({ code, value: field?.externalCodes?.[code] ?? code });
      continue;
    }

    if (raw && typeof raw === 'object') {
      for (const [code, value] of Object.entries(raw as Record<string, unknown>)) {
        items.push({ code, value: String(value) });
      }
    }
  }
  return items;
}

function metadataForField(
  field: EngineField | undefined,
  fallbackLabel: string,
  fallbackColumnName: string,
  index: number,
  resultValue: unknown,
): EditorMetadata {
  const columnName = field?.name ?? fallbackColumnName;
  return {
    index,
    label: fallbackLabel || columnName,
    desc: field?.unit ?? '',
    columnName,
    dataType: toEditorDataType(field?.typeRef),
    mandatory: field?.nullable === false,
    resultValue,
    enumCodeItems: enumCodeItems(field),
  };
}

function edgeEntryText(edge: GroupTree['edges'][number]): string {
  if (Array.isArray(edge.value)) return edge.value.map(String).join(',');
  if (edge.value !== undefined && edge.value !== null) return String(edge.value);
  return (edge.label ?? '').replace(/^=\s*/, '');
}

function toDecisionTreeEditorJson(envelope: RuleEnvelope, tree: GroupTree): DecisionTreeEditorJson {
  const inputsByName = fieldMap(getInputs(envelope));
  const outputsByName = fieldMap(getOutputs(envelope));
  const nodesById = new Map(tree.nodes.map((node) => [node.nodeId, node]));

  const nodes: EditorNode[] = tree.nodes.map((node) => {
    const isLeaf = node.type === 'LEAF';
    const outputs = node.outputs ?? node.results ?? [];
    const metadataList = isLeaf
      ? (outputs.length > 0
        ? outputs.map((output, index) => metadataForField(
          outputsByName.get(output.field),
          output.field,
          output.field,
          index,
          output.value,
        ))
        : [metadataForField(undefined, node.labelChinese ?? node.nodeId, '', 0, '')])
      : [metadataForField(
        inputsByName.get(node.fieldName ?? ''),
        node.labelChinese ?? node.fieldName ?? node.nodeId,
        node.fieldName ?? '',
        0,
        '',
      )];

    return {
      id: node.nodeId,
      type: 'custom',
      position: node.position ?? { x: 0, y: 0 },
      data: {
        columnType: isLeaf ? '1' : '0',
        metadataList,
        ruleIndex: null,
      },
    };
  });

  const edges: EditorEdge[] = tree.edges.map((edge, index) => {
    const sourceNode = nodesById.get(edge.from);
    return {
      id: `e${index + 1}_${edge.from}_${edge.to}`,
      source: edge.from,
      target: edge.to,
      data: {
        columnLabel: sourceNode?.labelChinese ?? sourceNode?.fieldName ?? '',
        columnName: sourceNode?.fieldName ?? '',
        expressionType: toEditorExpressionType(edge.operator),
        entryText: edgeEntryText(edge),
      },
    };
  });

  return { nodes, edges };
}

function normalizeResults(results: ExecutionResult['results']): GroupTreeOutput[] {
  if (!results) return [];
  if (Array.isArray(results)) return results;
  return Object.entries(results).map(([field, value]) => ({ field, value }));
}

function ErrorToast({ message, onDismiss }: { message: string; onDismiss: () => void }) {
  useEffect(() => {
    const id = window.setTimeout(onDismiss, 5000);
    return () => window.clearTimeout(id);
  }, [onDismiss]);

  return (
    <div className="fixed right-5 top-20 z-50 max-w-sm rounded-lg border px-4 py-3 shadow-lg bg-white border-danger/20 text-danger dark:bg-surface-2 dark:border-danger/30">
      <div className="flex items-start gap-3">
        <span className="mt-0.5 h-2 w-2 rounded-full bg-danger shrink-0" />
        <p className="text-xs leading-relaxed flex-1">{message}</p>
        <button type="button" onClick={onDismiss} className="text-xs cursor-pointer opacity-70 hover:opacity-100">
          x
        </button>
      </div>
    </div>
  );
}

function InputEditor({
  inputs,
  values,
  onChange,
}: {
  inputs: EngineField[];
  values: Record<string, unknown>;
  onChange: (field: EngineField, value: string) => void;
}) {
  const inputClass = `w-full rounded-lg border px-3 py-2 text-xs outline-none transition-colors
    dark:bg-surface-2 dark:border-border dark:text-text-primary dark:focus:border-accent/60
    bg-light-surface-2 border-light-border text-light-text-primary focus:border-accent/60`;

  if (inputs.length === 0) {
    return (
      <div className="rounded-lg border border-dashed p-4 text-xs dark:border-border dark:text-text-tertiary border-light-border text-light-text-tertiary">
        No input fields found in envelope.rule.inputs.
      </div>
    );
  }

  return (
    <div className="space-y-3">
      {inputs.map((field) => {
        const options = field.typeRef === 'ENUM' ? getOptions(field) : [];
        const value = values[field.name] ?? '';

        return (
          <label key={field.name} className="block">
            <span className="mb-1 flex items-center justify-between gap-2 text-[10px] font-medium dark:text-text-tertiary text-light-text-tertiary">
              <span className="truncate">{field.name}</span>
              <span className="shrink-0 rounded px-1.5 py-0.5 font-mono bg-accent/10 text-accent">
                {field.typeRef}
              </span>
            </span>

            {options.length > 0 ? (
              <select
                value={String(value)}
                onChange={(event) => onChange(field, event.target.value)}
                className={`${inputClass} cursor-pointer`}
              >
                <option value="">Select value</option>
                {options.map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </select>
            ) : field.typeRef === 'BOOLEAN' ? (
              <select
                value={value === '' ? '' : String(value)}
                onChange={(event) => onChange(field, event.target.value)}
                className={`${inputClass} cursor-pointer`}
              >
                <option value="">Select value</option>
                <option value="true">true</option>
                <option value="false">false</option>
              </select>
            ) : (
              <input
                type={field.typeRef === 'INTEGER' || field.typeRef === 'DECIMAL' ? 'number' : field.typeRef === 'DATE' ? 'date' : 'text'}
                step={field.typeRef === 'DECIMAL' ? '0.01' : undefined}
                value={String(value)}
                onChange={(event) => onChange(field, event.target.value)}
                className={inputClass}
              />
            )}
          </label>
        );
      })}
    </div>
  );
}

function ExecutionResultView({ result }: { result: ExecutionResult | null }) {
  if (!result) {
    return (
      <div className="rounded-lg border border-dashed p-4 text-xs dark:border-border dark:text-text-tertiary border-light-border text-light-text-tertiary">
        Run the engine to see hit path and outputs.
      </div>
    );
  }

  const allMatches = result.allMatches ?? [];
  const resultItems = normalizeResults(result.results);

  return (
    <div className="space-y-3">
      <div className={`rounded-lg border px-3 py-2 text-xs ${result.matched
        ? 'border-emerald-500/20 bg-emerald-500/10 text-emerald-600 dark:text-emerald-400'
        : 'border-amber-500/20 bg-amber-500/10 text-amber-600 dark:text-amber-400'}`}
      >
        {result.matched ? 'Matched' : 'No match'}
        {result.engineName && <span className="ml-2 opacity-70">· {result.engineName}</span>}
      </div>

      <div className="grid grid-cols-2 gap-2">
        <div className="rounded-lg p-3 dark:bg-surface-2 bg-light-surface-2">
          <p className="text-[10px] dark:text-text-tertiary text-light-text-tertiary">Hit rule</p>
          <p className="mt-1 font-mono text-xs font-semibold dark:text-text-primary text-light-text-primary">
            {result.hitRuleId ?? '-'}
          </p>
        </div>
        <div className="rounded-lg p-3 dark:bg-surface-2 bg-light-surface-2">
          <p className="text-[10px] dark:text-text-tertiary text-light-text-tertiary">Hit node</p>
          <p className="mt-1 font-mono text-xs font-semibold dark:text-text-primary text-light-text-primary">
            {result.hitNodeId ?? '-'}
          </p>
        </div>
      </div>

      {allMatches.length > 0 && (
        <div className="rounded-lg px-3 py-2 text-xs dark:bg-surface-2 bg-light-surface-2">
          <p className="mb-1 text-[10px] dark:text-text-tertiary text-light-text-tertiary">All matches</p>
          <div className="flex flex-wrap gap-1">
            {allMatches.map((match, index) => (
              <span key={`${match.hitRuleId ?? index}`} className="rounded px-1.5 py-0.5 font-mono bg-blue-500/10 text-blue-600 dark:text-blue-400">
                {match.hitRuleId ?? match.hitNodeId ?? `match-${index + 1}`}
              </span>
            ))}
          </div>
        </div>
      )}

      <div>
        <p className="mb-1 text-[10px] font-semibold uppercase tracking-wider dark:text-text-tertiary text-light-text-tertiary">
          Results
        </p>
        <div className="space-y-1">
          {resultItems.length > 0 ? resultItems.map((item, index) => (
            <div key={`${item.field}-${index}`} className="flex items-start gap-2 rounded-lg px-3 py-2 text-xs dark:bg-surface-2 bg-light-surface-2">
              <span className="shrink-0 font-mono dark:text-text-tertiary text-light-text-tertiary">{item.field}</span>
              <span className="min-w-0 break-words font-semibold dark:text-text-primary text-light-text-primary">
                {String(item.value)}
              </span>
            </div>
          )) : (
            <p className="rounded-lg px-3 py-2 text-xs dark:bg-surface-2 dark:text-text-tertiary bg-light-surface-2 text-light-text-tertiary">
              No outputs returned.
            </p>
          )}
        </div>
      </div>

      <div>
        <p className="mb-1 text-[10px] font-semibold uppercase tracking-wider dark:text-text-tertiary text-light-text-tertiary">
          Evaluation path
        </p>
        <div className="max-h-48 space-y-1 overflow-y-auto">
          {(result.evaluationPath ?? []).map((step, index) => (
            <div
              key={`${step}-${index}`}
              className={`rounded px-2 py-1 text-[11px] ${step.includes('MATCHED') || step.includes('leaf hit')
                ? 'bg-blue-500/10 text-blue-600 dark:text-blue-400'
                : 'dark:bg-surface-2 dark:text-text-secondary bg-light-surface-2 text-light-text-secondary'}`}
            >
              {index + 1}. {step}
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

export default function EngineExecutionTab({ envelope }: EngineExecutionTabProps) {
  const inputs = useMemo(() => getInputs(envelope), [envelope]);
  const [inputValues, setInputValues] = useState<Record<string, unknown>>({});
  const [tree, setTree] = useState<GroupTree | null>(null);
  const [warnings, setWarnings] = useState<string[]>([]);
  const [executionResult, setExecutionResult] = useState<ExecutionResult | null>(null);
  const [treeLoading, setTreeLoading] = useState(false);
  const [runLoading, setRunLoading] = useState(false);
  const [xlsxLoading, setXlsxLoading] = useState(false);
  const [jsonLoading, setJsonLoading] = useState(false);
  const [editorJsonLoading, setEditorJsonLoading] = useState(false);
  const [treeError, setTreeError] = useState<string | null>(null);
  const [toast, setToast] = useState<string | null>(null);

  const showError = useCallback((message: string) => {
    setToast(message);
  }, []);

  useEffect(() => {
    setInputValues((current) => {
      const next: Record<string, unknown> = {};
      for (const field of inputs) {
        next[field.name] = current[field.name] ?? '';
      }
      return next;
    });
  }, [inputs]);

  useEffect(() => {
    let cancelled = false;
    setTreeLoading(true);
    setTreeError(null);
    setExecutionResult(null);

    api.exportGroupJson(envelope)
      .then((response) => {
        if (cancelled) return;
        setTree(response.tree);
        setWarnings(response.warnings ?? []);
      })
      .catch((err: { message?: string }) => {
        if (cancelled) return;
        const message = err.message ?? 'Failed to export Group JSON';
        setTree(null);
        setTreeError(message);
        showError(message);
      })
      .finally(() => {
        if (!cancelled) setTreeLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [envelope, showError]);

  const handleInputChange = useCallback((field: EngineField, value: string) => {
    setInputValues((current) => ({
      ...current,
      [field.name]: coerceInputValue(field, value),
    }));
  }, []);

  const handleRun = useCallback(async () => {
    setRunLoading(true);
    setExecutionResult(null);
    try {
      const result = await api.execute(envelope, inputValues);
      setExecutionResult(result);
    } catch (err: unknown) {
      const message = (err as { message?: string }).message ?? 'Execution failed';
      showError(message);
    } finally {
      setRunLoading(false);
    }
  }, [envelope, inputValues, showError]);

  const handleDownloadXlsx = useCallback(async () => {
    setXlsxLoading(true);
    try {
      const response = await api.exportGroupXlsx(envelope);
      if (response.warnings.length > 0) {
        setWarnings(response.warnings);
      }
      saveBlob(response.blob, response.filename);
    } catch (err: unknown) {
      const message = (err as { message?: string }).message ?? 'Group XLSX export failed';
      showError(message);
    } finally {
      setXlsxLoading(false);
    }
  }, [envelope, showError]);

  const handleDownloadJson = useCallback(async () => {
    setJsonLoading(true);
    try {
      const response = await api.exportGroupJson(envelope);
      setTree(response.tree);
      setWarnings(response.warnings ?? []);
      saveTreeJson(response.tree);
    } catch (err: unknown) {
      const message = (err as { message?: string }).message ?? 'Group JSON export failed';
      showError(message);
    } finally {
      setJsonLoading(false);
    }
  }, [envelope, showError]);

  const handleDownloadEditorJson = useCallback(async () => {
    setEditorJsonLoading(true);
    try {
      const response = await api.exportGroupJson(envelope);
      setTree(response.tree);
      setWarnings(response.warnings ?? []);
      saveJson(toDecisionTreeEditorJson(envelope, response.tree), `decision-tree-editor-${Date.now()}.json`);
    } catch (err: unknown) {
      const message = (err as { message?: string }).message ?? 'Decision tree editor JSON export failed';
      showError(message);
    } finally {
      setEditorJsonLoading(false);
    }
  }, [envelope, showError]);

  return (
    <div className="space-y-4">
      {toast && <ErrorToast message={toast} onDismiss={() => setToast(null)} />}

      {warnings.length > 0 && (
        <div className="rounded-xl border px-4 py-3 text-xs border-amber-500/20 bg-amber-500/10 text-amber-600 dark:text-amber-400">
          {warnings.join(' · ')}
        </div>
      )}

      <div className="grid grid-cols-1 gap-4 xl:grid-cols-3">
        <JsonViewer envelope={envelope} />

        <GroupTreeView
          tree={tree}
          loading={treeLoading}
          error={treeError}
          hitPath={executionResult?.hitPath ?? []}
          hitNodeId={executionResult?.hitNodeId}
          hitRuleId={executionResult?.hitRuleId}
        />

        <div className="rounded-xl border overflow-hidden dark:bg-surface-1 dark:border-border bg-white border-light-border">
          <div className="flex items-center justify-between px-4 py-3 border-b dark:border-border/50 border-light-border/50">
            <div>
              <h3 className="text-sm font-semibold dark:text-text-primary text-light-text-primary">
                Test Run
              </h3>
              <p className="text-[10px] dark:text-text-tertiary text-light-text-tertiary">
                POST /tools/execute
              </p>
            </div>
            <button
              type="button"
              onClick={handleRun}
              disabled={runLoading}
              className="rounded-lg px-3 py-1.5 text-xs font-semibold text-white bg-accent hover:bg-accent/90 disabled:cursor-wait disabled:opacity-60 cursor-pointer"
            >
              {runLoading ? 'Running...' : 'Run'}
            </button>
          </div>

          <div className="space-y-5 p-4">
            <section>
              <div className="mb-2 flex items-center justify-between gap-2">
                <p className="text-[10px] font-semibold uppercase tracking-wider dark:text-text-tertiary text-light-text-tertiary">
                  Input values
                </p>
                <span className="text-[10px] dark:text-text-tertiary text-light-text-tertiary">
                  {inputs.length} fields
                </span>
              </div>
              <InputEditor inputs={inputs} values={inputValues} onChange={handleInputChange} />
            </section>

            <section className="flex flex-wrap gap-2">
              <button
                type="button"
                onClick={handleDownloadXlsx}
                disabled={xlsxLoading}
                className="rounded-lg px-3 py-2 text-xs font-semibold cursor-pointer bg-blue-500/10 text-blue-600 hover:bg-blue-500/20 disabled:cursor-wait disabled:opacity-60 dark:text-blue-400"
              >
                {xlsxLoading ? 'Downloading...' : 'Download Group XLSX'}
              </button>
              <button
                type="button"
                onClick={handleDownloadJson}
                disabled={jsonLoading}
                className="rounded-lg px-3 py-2 text-xs font-semibold cursor-pointer dark:bg-surface-2 dark:text-text-secondary dark:hover:bg-surface-3 bg-light-surface-2 text-light-text-secondary hover:bg-light-surface-3 disabled:cursor-wait disabled:opacity-60"
              >
                {jsonLoading ? 'Downloading...' : 'Download Group JSON'}
              </button>
              <button
                type="button"
                onClick={handleDownloadEditorJson}
                disabled={editorJsonLoading}
                className="rounded-lg px-3 py-2 text-xs font-semibold cursor-pointer dark:bg-surface-2 dark:text-text-secondary dark:hover:bg-surface-3 bg-light-surface-2 text-light-text-secondary hover:bg-light-surface-3 disabled:cursor-wait disabled:opacity-60"
              >
                {editorJsonLoading ? 'Downloading...' : 'Download Editor JSON'}
              </button>
            </section>

            <section>
              <p className="mb-2 text-[10px] font-semibold uppercase tracking-wider dark:text-text-tertiary text-light-text-tertiary">
                Result
              </p>
              <ExecutionResultView result={executionResult} />
            </section>
          </div>
        </div>
      </div>
    </div>
  );
}
