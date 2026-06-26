import { useState, useEffect } from 'react';
import { motion, AnimatePresence } from 'framer-motion';

const STORAGE_KEY = 'rules-mcp-welcome-dismissed';

interface Props {
  onSelectDemo?: () => void;
}

const STEPS = [
  {
    icon: '1',
    title: '輸入業務規格',
    desc: '用自然語言說明條件、檢核範圍與錯誤訊息。例如：「ID 格式與國籍不一致時拋出對應錯誤」',
  },
  {
    icon: '2',
    title: '整理為中介規則模型',
    desc: '系統解析描述，建立 engine-neutral RuleEnvelope，支援決策表、多重命中與決策樹',
  },
  {
    icon: '3',
    title: '驗證後交付轉換',
    desc: '檢查覆蓋、衝突與情境展開；輸出報告與 JSON/CSV，供集團規則引擎 adapter 轉換',
  },
];

export default function WelcomeGuide({ onSelectDemo }: Props) {
  const [visible, setVisible] = useState(false);
  const [neverShow, setNeverShow] = useState(false);

  useEffect(() => {
    const dismissed = localStorage.getItem(STORAGE_KEY);
    if (!dismissed) setVisible(true);
  }, []);

  const handleDismiss = () => {
    if (neverShow) localStorage.setItem(STORAGE_KEY, 'true');
    setVisible(false);
  };

  // 鍵盤無障礙：Escape 關閉
  useEffect(() => {
    if (!visible) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') handleDismiss();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [visible, neverShow]);

  return (
    <AnimatePresence>
      {visible && (
        <motion.div
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          className="fixed inset-0 z-[100] flex items-center justify-center bg-black/50 backdrop-blur-sm p-4"
          onClick={(e) => { if (e.target === e.currentTarget) handleDismiss(); }}
          role="dialog"
          aria-modal="true"
          aria-labelledby="welcome-guide-title"
        >
          <motion.div
            initial={{ scale: 0.95, opacity: 0 }}
            animate={{ scale: 1, opacity: 1 }}
            exit={{ scale: 0.95, opacity: 0 }}
            className="w-full max-w-lg rounded-2xl border overflow-hidden
              dark:bg-surface-1 dark:border-border bg-white border-light-border shadow-2xl"
          >
            {/* Header */}
            <div className="px-6 pt-6 pb-4 text-center">
              <div className="w-12 h-12 rounded-xl bg-accent/15 flex items-center justify-center mx-auto mb-3">
                <svg width="24" height="24" viewBox="0 0 14 14" fill="none">
                  <rect x="1" y="1" width="5" height="5" rx="1" fill="currentColor" className="text-accent" />
                  <rect x="8" y="1" width="5" height="5" rx="1" fill="currentColor" className="text-accent opacity-60" />
                  <rect x="1" y="8" width="5" height="5" rx="1" fill="currentColor" className="text-accent opacity-60" />
                  <rect x="8" y="8" width="5" height="5" rx="1" fill="currentColor" className="text-accent opacity-30" />
                </svg>
              </div>
              <h2 id="welcome-guide-title" className="text-lg font-bold dark:text-text-primary text-light-text-primary">
                歡迎使用業務規則治理助手
              </h2>
              <p className="text-xs mt-1 dark:text-text-tertiary text-light-text-tertiary">
                把文字規格整理為可審核、可測試、可交付轉換的 RuleEnvelope
              </p>
            </div>

            {/* Steps */}
            <div className="px-6 pb-4 space-y-3">
              {STEPS.map((step) => (
                <div key={step.icon} className="flex gap-3 items-start">
                  <div className="w-7 h-7 rounded-full bg-accent/15 text-accent flex items-center justify-center text-xs font-bold shrink-0 mt-0.5">
                    {step.icon}
                  </div>
                  <div>
                    <p className="text-sm font-semibold dark:text-text-primary text-light-text-primary">
                      {step.title}
                    </p>
                    <p className="text-xs dark:text-text-secondary text-light-text-secondary leading-relaxed">
                      {step.desc}
                    </p>
                  </div>
                </div>
              ))}
            </div>

            {/* Tips */}
            <div className="mx-6 p-3 rounded-lg dark:bg-surface-2 bg-light-surface-2 mb-4">
              <p className="text-[11px] dark:text-text-secondary text-light-text-secondary leading-relaxed">
                <span className="font-semibold">撰寫小撇步：</span>
                請把「檢核範圍、條件、例外、錯誤訊息」寫清楚。此工具產出的是 RuleEnvelope 中介模型，
                不是直接綁定某一套集團規則引擎的部署檔；正式導入前會再透過 adapter 轉換欄位代碼與執行格式。
              </p>
            </div>

            {/* Actions */}
            <div className="px-6 pb-6 flex items-center justify-between">
              <label className="flex items-center gap-2 cursor-pointer">
                <input
                  type="checkbox"
                  checked={neverShow}
                  onChange={(e) => setNeverShow(e.target.checked)}
                  className="w-3.5 h-3.5 rounded accent-accent"
                />
                <span className="text-[11px] dark:text-text-tertiary text-light-text-tertiary">
                  不再顯示
                </span>
              </label>
              <div className="flex gap-2">
                {onSelectDemo && (
                  <button
                    onClick={() => { onSelectDemo(); handleDismiss(); }}
                    className="px-4 py-2 rounded-lg text-xs font-medium cursor-pointer
                      dark:bg-surface-2 dark:text-text-secondary dark:hover:bg-surface-3
                      bg-light-surface-2 text-light-text-secondary hover:bg-light-surface-3 transition-colors"
                  >
                    查看範例
                  </button>
                )}
                <button
                  onClick={handleDismiss}
                  className="px-4 py-2 rounded-lg text-xs font-semibold text-white
                    bg-accent hover:bg-accent/90 transition-colors cursor-pointer"
                >
                  開始使用
                </button>
              </div>
            </div>
          </motion.div>
        </motion.div>
      )}
    </AnimatePresence>
  );
}
