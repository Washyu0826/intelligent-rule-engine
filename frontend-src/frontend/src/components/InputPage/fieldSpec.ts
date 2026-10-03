export interface ConfirmedField {
  name: string;
  typeRef: string;
  values: string;
}

export interface ConfirmedFields {
  inputs: ConfirmedField[];
  outputs: ConfirmedField[];
}

export function describeFields(f: ConfirmedFields): string {
  const line = (x: ConfirmedField) => `- ${x.name}（${x.typeRef}${x.values.trim() ? '：' + x.values.trim() : ''}）`;
  return [
    '【已確認的欄位，請嚴格依此產生，不要新增或改名】',
    '輸入欄位：',
    ...f.inputs.filter((x) => x.name.trim()).map(line),
    '輸出欄位：',
    ...f.outputs.filter((x) => x.name.trim()).map(line),
  ].join('\n');
}
