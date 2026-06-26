export interface DemoScenario {
  id: string;
  label: string;
  description: string;
  badge?: string;
  category: 'insurance' | 'finance' | 'commerce' | 'test';
  ruleType?: 'DecisionTable' | 'DecisionTree';
  difficulty?: 'simple' | 'medium' | 'complex';
}

export const SCENARIO_CATEGORIES = [
  { id: 'insurance', label: '保險', color: 'text-blue-500 bg-blue-500/10' },
] as const;

export const DEMO_SCENARIOS: DemoScenario[] = [
  {
    id: 'id-nationality',
    label: 'ID 與國籍交叉檢核',
    category: 'insurance',
    difficulty: 'medium',
    badge: 'Demo',
    description:
      '1. 若[被保人ID]為[中華民國身分證字號格式]且[被保人國籍別]不為[TW]，則拋訊息 1.1 被保人ID為身分證格式，國籍別請選擇中華民國。\n' +
      '2. 若[被保人ID]不為[中華民國身分證字號格式]且[被保人國籍別]為[TW]，則拋訊息 1.2 被保人ID不為身分證格式，國籍別請選擇非中華民國。\n' +
      '3. 若[要保人ID]為[中華民國身分證字號格式]且[要保人國籍別]不為[TW]，則拋訊息 1.3 要保人ID為身分證格式，國籍別請選擇中華民國。\n' +
      '4. 若[要保人ID]不為[中華民國身分證字號格式]且[要保人國籍別]為[TW]，則拋訊息 1.4 要保人ID不為身分證格式，國籍別請選擇非中華民國。\n' +
      '採 MULTI hit policy，被保人與要保人各自違規時同時回報多重錯誤碼。',
  },
  {
    id: 'receiving-channel-checks',
    label: '受理通路綜合檢核',
    category: 'insurance',
    difficulty: 'complex',
    badge: 'Demo',
    description:
      '檢核範圍：受理通路為[行動保險]或[網路投保]或[直效線上成交]\n\n' +
      '【授權書編號合理性】\n' +
      '2.1 若通路為[保代]或[直效] 且 [授權書編號]為[受理編號]，則檢核[授權書編號]應符合[受理編號規則]，否則拋出訊息。\n' +
      '2.2 若通路為[保代]或[直效]或[特約] 且 [授權書編號]不為[受理編號]，則檢核[授權書編號]應符合[有效授權書編號規則]，否則拋出訊息。\n' +
      '2.3 若通路為[特約] 且 [授權書編號]為[受理編號]，則拋出錯誤訊息。\n\n' +
      '【繳費管道合理性】\n' +
      '3.1 若[新契約繳費管道]或[續期繳費管道]為[指定帳戶轉帳]，則[要保人ID]須為[業務員]或[行政人員]，否則拋出訊息。\n' +
      '3.2 若[新契約繳費管道]或[續期繳費管道]為[A7扣薪]，則[要保人ID]須為[在職員工]，否則拋出訊息。\n' +
      '3.3 若[新契約繳費管道]或[續期繳費管道]為[A8扣薪]，則[團體種類]須為[6]，否則拋出訊息。\n\n' +
      '【投保始期】\n' +
      '4.1 [非網投件]的[新契約繳費管道]或[續期繳費管道]不為[滿期金]或[解約金]，則[投保始期]不可超過[隔日]，否則拋出訊息。\n' +
      '4.2 所有案件，[投保始期]不可小於[被保人生日]，否則拋出訊息。\n\n' +
      '註1：在試算上傳中，保代通路不會進行檢核。\n' +
      '採 MULTI hit policy 一次回報所有違規。',
  },
];
