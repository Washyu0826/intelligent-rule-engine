// English camelCase field name → 中文 顯示標籤。
// 鏡像後端 GroupLabelResolver 的 SHIM 字典，讓缺口卡片的欄位名與 xlsx / 樹匯出一致。
// 解析順序：已是中文 → 整名命中 → camelCase 逐 token 命中 → 原樣 fallback。

const SHIM: Record<string, string> = {
  // sample-A（ID / 國籍別交叉檢核）
  insuredidformatisrocid: '被保人 ID 為 ROC 格式',
  insurednationality: '被保人國籍',
  policyholderidformatisrocid: '要保人 ID 為 ROC 格式',
  policyholdernationality: '要保人國籍',

  // sample-B（通路 / 授權書 / 繳費 / 投保始期）
  channel: '通路',
  receivingchannel: '受理通路',
  authnumber: '授權書編號',
  acceptancenumber: '受理編號',
  authmatchesacceptanceformat: '授權書符合有效授權書編號格式',
  authmatchesacceptancenumber: '授權書編號等於受理編號',
  auth: '授權書',
  matches: '符合',
  acceptance: '受理',
  newcontractpaymentchannel: '新契約繳費管道',
  renewalpaymentchannel: '續期繳費管道',
  paymentchannel: '繳費管道',
  policystartdate: '投保始期',
  policystartdateinsured: '投保始期（被保人）',
  policystartdateproposer: '投保始期（要保人）',
  insuredbirthday: '被保人生日',
  proposerbirthday: '要保人生日',

  // 共用輸出欄位
  errormessage: '錯誤訊息',
  decision: '核保決議',
  applicable: '適用',
  premiumrate: '保費係數',
  remark: '備註說明',
  premium: '保費',
  status: '狀態',

  // 內建 12 個 BU 範例 — 健康 / 保險 / 評分
  age: '年齡',
  insuredage: '投保年齡',
  gender: '性別',
  bmi: 'BMI',
  bmirange: 'BMI 區間',
  hashypertension: '是否有高血壓',
  hasdiabetes: '是否有糖尿病',
  hasheartdisease: '是否有心臟病',
  smokingstatus: '吸菸狀態',
  occupationrisklevel: '職業風險等級',
  claimcount: '理賠次數',
  creditscore: '信用評分',

  // 通用短 token（camelCase 拆字 fallback 用）
  insured: '被保人',
  policyholder: '要保人',
  proposer: '要保人',
  beneficiary: '受益人',
  id: 'ID',
  rocid: 'ROC 身分證',
  format: '格式',
  number: '編號',
  nationality: '國籍別',
  birthday: '生日',
  date: '日期',
  type: '類型',
  category: '類別',
  amount: '金額',
  score: '分數',
  level: '等級',
  hypertension: '高血壓',
  diabetes: '糖尿病',
  heartdisease: '心臟病',
  smoking: '吸菸',
  occupation: '職業',
  risk: '風險',
  count: '次數',
  credit: '信用',
};

const CJK = /[㐀-鿿豈-﫿]/;

/** 拆 camelCase / snake_case 成 token（不依賴 lookbehind，相容舊瀏覽器）。 */
function splitCamel(name: string): string[] {
  return name
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .split(/[\s_]+/)
    .filter(Boolean);
}

/** 欄位英文名 → 中文標籤。無對照時回傳原字串。 */
export function fieldLabel(name: string): string {
  if (!name) return '(空)';
  if (CJK.test(name)) return name; // 已是中文，直接用

  const whole = SHIM[name.toLowerCase()];
  if (whole) return whole;

  const tokens = splitCamel(name);
  let anyHit = false;
  const parts = tokens.map((t) => {
    const hit = SHIM[t.toLowerCase()];
    if (hit) {
      anyHit = true;
      return hit;
    }
    return t;
  });
  return anyHit ? parts.join(' ') : name;
}

/** 把布林字面值轉成中文；其餘原樣回傳。 */
export function humanizeValue(value: string): string {
  if (value === 'true') return '是';
  if (value === 'false') return '否';
  return value;
}
