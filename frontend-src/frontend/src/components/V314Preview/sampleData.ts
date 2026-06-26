/**
 * v3.14 Preview UI 用的範例 mock data。
 * - case1 規則：取自後端 fixtures/case1-id-nationality.json（保持同步）
 * - glossary：摘自 v3.14 字彙表 starter 研究結果（agent M 110 條的子集）
 * - annotations：示範 inline annotation 的內容（BA / IT / 法遵三方）
 *
 * 此檔僅供 V314Preview 元件展示用，**不被生產 UI 流程引用**。
 */

// ─────────────────────────────────────────
// 5 層分層架構的範例規則（case 1 R01 為例）
// ─────────────────────────────────────────

export interface GlossaryEntry {
  id: string;
  zh_TW: string;
  en: string;
  synonyms?: string[];
  definition: string;
  source: string;
  category: '核保' | '商品' | '理賠' | '通路' | '繳費' | '合規' | '精算';
  fieldCode?: string;
  status: 'active' | 'deprecated' | 'proposed';
  deprecatedAt?: string;
  successor?: string;
}

export interface LayerAnnotation {
  id: string;
  layer: 'L1' | 'L2' | 'L3' | 'L4' | 'L5';
  lineRef: string;
  author: string;
  role: 'BA' | 'IT' | '法遵' | '稽核';
  content: string;
  createdAt: string;
  resolved?: boolean;
}

export interface LayeredRule {
  ruleId: string;
  schemaVersion: string;
  promptVersion: string;
  // L1 BA 摘要層
  narrative: {
    summary: string;
    tags: string[];
  };
  // L2 業務語意層
  businessSemantic: {
    scope: string | null;
    conditions: Array<{
      fieldTerm: string;
      operatorTerm: string;
      valueTerm: string;
    }>;
    resultTerm: string;
  };
  // L3 規則結構層（既有 RuleEnvelope.rule 簡化版）
  ruleStructure: {
    ruleType: 'DecisionTable';
    hitPolicy: 'ALL_FAIL';
    inputs: Array<{ name: string; typeRef: string; allowedValues?: string[] }>;
    output: { name: string; typeRef: string };
    severity: 'ERROR' | 'WARNING' | 'INFO';
    errorMessage: string;
  };
  // L4 執行表達層
  executionLayer: Array<{
    field: string;
    operator: string;
    value: string | boolean;
  }>;
  // L5 整合對映層
  integration: {
    fieldMappings: Record<
      string,
      { externalSystem: string; externalField: string; transform?: string }
    >;
  };
  // 共用 metadata
  audit: {
    businessOwner: string;
    techOwner: string;
    regulatoryRef: string;
    effectiveDate: string;
    version: string;
    reviewStatus: 'draft' | 'in_review' | 'approved' | 'deployed';
    reviewers: string[];
  };
}

// ─────────────────────────────────────────
// 範例規則：case 1 R01（被保人 ID 為身分證但國籍非 TW）
// ─────────────────────────────────────────
export const SAMPLE_RULE: LayeredRule = {
  ruleId: 'R01',
  schemaVersion: '1.0.0',
  promptVersion: 'p3.14.0',
  narrative: {
    summary: '若被保人 ID 為中華民國身分證格式但國籍別非 TW，拋訊息 1.1。',
    tags: ['核保', 'ID 一致性', 'KYC/CDD'],
  },
  businessSemantic: {
    scope: null,
    conditions: [
      {
        fieldTerm: '被保人 ID 格式',
        operatorTerm: '為',
        valueTerm: '中華民國身分證字號格式',
      },
      {
        fieldTerm: '被保人國籍別',
        operatorTerm: '不為',
        valueTerm: 'TW（中華民國）',
      },
    ],
    resultTerm: '錯誤訊息 1.1：被保人 ID 為身分證格式，國籍別請選擇中華民國',
  },
  ruleStructure: {
    ruleType: 'DecisionTable',
    hitPolicy: 'ALL_FAIL',
    inputs: [
      { name: 'insuredIdFormatIsRocId', typeRef: 'BOOLEAN' },
      { name: 'insuredNationality', typeRef: 'ENUM', allowedValues: ['TW', 'NON_TW'] },
    ],
    output: { name: 'errorMessage', typeRef: 'STRING' },
    severity: 'ERROR',
    errorMessage: '1.1 被保人ID為身分證格式，國籍別請選擇中華民國',
  },
  executionLayer: [
    { field: 'insuredIdFormatIsRocId', operator: 'equals', value: true },
    { field: 'insuredNationality', operator: 'equals', value: 'NON_TW' },
  ],
  integration: {
    fieldMappings: {
      insuredIdFormatIsRocId: {
        externalSystem: 'POLICY_CORE',
        externalField: 'INSURED_ID_TYPE_FLAG',
        transform: 'isRocIdFormat()',
      },
      insuredNationality: {
        externalSystem: 'POLICY_CORE',
        externalField: 'INSURED_NATIONALITY_CODE',
      },
    },
  },
  audit: {
    businessOwner: 'underwriting@grouplife',
    techOwner: 'platform-team@grouplife',
    regulatoryRef: '金管會 AML 指引 §3 + 保險法 §3, §4',
    effectiveDate: '2026-05-13',
    version: '1.0',
    reviewStatus: 'in_review',
    reviewers: ['Alice (BA)', 'Bob (IT)'],
  },
};

// ─────────────────────────────────────────
// 字彙表（starter 子集，~25 條）
// ─────────────────────────────────────────
export const GLOSSARY: GlossaryEntry[] = [
  {
    id: 'insured',
    zh_TW: '被保險人',
    en: 'Insured',
    synonyms: ['被保人'],
    definition: '保險事故發生時遭受損害、享賠償請求權之人',
    source: '保險法 §4',
    category: '核保',
    fieldCode: 'INSURED',
    status: 'active',
  },
  {
    id: 'proposer',
    zh_TW: '要保人',
    en: 'Proposer',
    synonyms: ['投保人'],
    definition: '與保險人訂約並負繳費義務之人',
    source: '保險法 §3',
    category: '核保',
    fieldCode: 'POLICYHOLDER',
    status: 'active',
  },
  {
    id: 'beneficiary',
    zh_TW: '受益人',
    en: 'Beneficiary',
    definition: '經約定享有保險金請求權之人',
    source: '保險法 §5',
    category: '核保',
    status: 'active',
  },
  {
    id: 'roc-id',
    zh_TW: '中華民國身分證字號',
    en: 'ROC National ID',
    synonyms: ['身分證號', '國民身分證統一編號'],
    definition: '中華民國國民身分證統一編號（10 碼：1 字母 + 9 數字 + 校驗碼）',
    source: '戶籍法',
    category: '合規',
    fieldCode: 'NATIONAL_ID',
    status: 'active',
  },
  {
    id: 'nationality',
    zh_TW: '國籍別',
    en: 'Nationality',
    definition: '客戶法律上的國籍歸屬，AML/FATCA/CRS 申報依據',
    source: '金融機構防制洗錢辦法 §3',
    category: '合規',
    fieldCode: 'NATIONALITY_CODE',
    status: 'active',
  },
  {
    id: 'sum-insured',
    zh_TW: '保額',
    en: 'Sum Insured',
    synonyms: ['保險金額'],
    definition: '保險人最高給付額度',
    source: '保險法 §72',
    category: '商品',
    fieldCode: 'SUM_INSURED',
    status: 'active',
  },
  {
    id: 'premium',
    zh_TW: '保費',
    en: 'Premium',
    synonyms: ['保險費'],
    definition: '要保人應繳之對價',
    source: '保險法 §21',
    category: '商品',
    fieldCode: 'PREMIUM',
    status: 'active',
  },
  {
    id: 'waiting-period',
    zh_TW: '等待期',
    en: 'Waiting Period',
    definition: '契約生效後一定期間內事故不予給付（常見癌症 90 日）',
    source: '人壽保險單示範條款',
    category: '核保',
    status: 'active',
  },
  {
    id: 'disability',
    zh_TW: '失能',
    en: 'Disability',
    synonyms: ['殘廢'],
    definition: '喪失身體 / 精神機能至條款表定等級',
    source: '失能程度與保險金給付表（107 年修訂）',
    category: '理賠',
    status: 'active',
  },
  {
    id: 'disability-old',
    zh_TW: '殘廢',
    en: 'Disability (legacy)',
    definition: '2018-06-15 公會示範條款修訂後改稱「失能」',
    source: '人壽公會 107 年示範條款修訂',
    category: '理賠',
    status: 'deprecated',
    deprecatedAt: '2018-06-15',
    successor: 'disability',
  },
  {
    id: 'grace-period',
    zh_TW: '寬限期',
    en: 'Grace Period',
    definition: '保費到期催告後 30 日內仍可繳付',
    source: '保險法 §116',
    category: '繳費',
    status: 'active',
  },
  {
    id: 'lapse',
    zh_TW: '停效',
    en: 'Lapse',
    definition: '寬限期屆滿未繳保費，契約效力停止',
    source: '保險法 §116',
    category: '繳費',
    status: 'active',
  },
  {
    id: 'channel-agent',
    zh_TW: '保代',
    en: 'Agent Company',
    synonyms: ['保險代理人'],
    definition: '代理保險人招攬之公司',
    source: '保險法 §8',
    category: '通路',
    status: 'active',
  },
  {
    id: 'channel-broker',
    zh_TW: '保經',
    en: 'Broker',
    synonyms: ['保險經紀人'],
    definition: '基於被保險人利益洽訂契約之人/公司',
    source: '保險法 §9',
    category: '通路',
    status: 'active',
  },
  {
    id: 'mobile-insurance',
    zh_TW: '行動投保',
    en: 'Mobile Underwriting',
    definition: '業務員以行動載具（iPad）完成要保',
    source: '公司實務（待確認）',
    category: '通路',
    status: 'proposed',
  },
  {
    id: 'online-insurance',
    zh_TW: '網路投保',
    en: 'Online Insurance',
    definition: '透過保險業網站直接投保',
    source: '保險業辦理電子商務應注意事項',
    category: '通路',
    status: 'active',
  },
  {
    id: 'fatca',
    zh_TW: 'FATCA',
    en: 'Foreign Account Tax Compliance Act',
    synonyms: ['肥咖條款'],
    definition: '美國海外帳戶稅收遵循法，識別美籍客戶',
    source: '金融機構執行 FATCA 作業辦法',
    category: '合規',
    status: 'active',
  },
  {
    id: 'aml',
    zh_TW: '防制洗錢',
    en: 'AML',
    synonyms: ['Anti-Money Laundering'],
    definition: '防止洗錢之機制',
    source: '洗錢防制法',
    category: '合規',
    status: 'active',
  },
  {
    id: 'kyc',
    zh_TW: '客戶身分確認',
    en: 'KYC',
    synonyms: ['Know Your Customer'],
    definition: '客戶身分確認程序',
    source: '金融機構防制洗錢辦法 §3',
    category: '合規',
    status: 'active',
  },
  {
    id: 'reserve',
    zh_TW: '責任準備金',
    en: 'Policy Reserve',
    definition: '保險公司為未來給付義務提存之準備',
    source: '保險法 §145',
    category: '商品',
    status: 'active',
  },
  {
    id: 'policy-value',
    zh_TW: '保價金',
    en: 'Policy Value Reserve',
    synonyms: ['保單價值準備金'],
    definition: '已繳保費扣除成本後依約定利率累積之帳戶價值',
    source: '保險業會計處理準則',
    category: '商品',
    status: 'active',
  },
  {
    id: 'surrender-value',
    zh_TW: '解約金',
    en: 'Surrender Value',
    synonyms: ['保單現金價值'],
    definition: '解約退還金額，繳費滿一年不得低於保價金 3/4',
    source: '保險法 §119',
    category: '商品',
    status: 'active',
  },
  {
    id: 'maturity-benefit',
    zh_TW: '滿期金',
    en: 'Maturity Benefit',
    definition: '契約屆滿仍生存之給付',
    source: '人壽保險單示範條款',
    category: '商品',
    status: 'active',
  },
  {
    id: 'critical-illness',
    zh_TW: '重大疾病',
    en: 'Critical Illness',
    synonyms: ['重疾'],
    definition: '條款列舉七項（心肌梗塞、癌症等）',
    source: '重大疾病險示範條款',
    category: '理賠',
    status: 'active',
  },
  {
    id: 'insurance-age',
    zh_TW: '保險年齡',
    en: 'Insurance Age',
    synonyms: ['投保年齡'],
    definition: '計算費率用之年齡，採足歲加未滿一年逾六個月進位',
    source: '人壽保險單示範條款',
    category: '精算',
    status: 'active',
  },
];

// ─────────────────────────────────────────
// 示範 annotations（BA / IT / 法遵 三方留言）
// ─────────────────────────────────────────
export const ANNOTATIONS: LayerAnnotation[] = [
  {
    id: 'a1',
    layer: 'L1',
    lineRef: 'summary',
    author: 'Alice (BA)',
    role: 'BA',
    content: '建議補上「為何要做這條檢核」的業務理由 — 主要是 AML/CDD 資料品質防呆，避免外籍客戶被誤填中華民國。',
    createdAt: '2026-05-13 14:32',
  },
  {
    id: 'a2',
    layer: 'L2',
    lineRef: 'condition-1',
    author: 'Carol (法遵)',
    role: '法遵',
    content: '「中華民國身分證字號格式」需明確排除新式外來人口統一證號（第 2 碼 8/9）— 否則會誤觸發此規則。已開出問題待 BU 端確認 A1。',
    createdAt: '2026-05-13 15:10',
  },
  {
    id: 'a3',
    layer: 'L4',
    lineRef: 'insuredIdFormatIsRocId',
    author: 'Bob (IT)',
    role: 'IT',
    content: 'caller 端要記得呼叫 RocIdValidator.isRocId() 算出 boolean 再送進來。已寫好 helper 在 service/util/。',
    createdAt: '2026-05-13 16:45',
    resolved: true,
  },
  {
    id: 'a4',
    layer: 'L5',
    lineRef: 'NATIONALITY_CODE',
    author: 'Bob (IT)',
    role: 'IT',
    content: '要確認 POLICY_CORE 系統的 NATIONALITY_CODE 編碼是 ISO 3166-1 alpha-2 還是內部代碼。已開出問題待 BU 端確認 A2。',
    createdAt: '2026-05-13 17:02',
  },
];
