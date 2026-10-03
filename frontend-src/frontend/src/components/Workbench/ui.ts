export const card =
  'rounded-xl border dark:bg-surface-1 dark:border-border bg-white border-light-border';
export const textPrimary = 'dark:text-text-primary text-light-text-primary';
export const textSecondary = 'dark:text-text-secondary text-light-text-secondary';
export const textTertiary = 'dark:text-text-tertiary text-light-text-tertiary';
export const input =
  'w-full rounded-lg border px-3 py-2 text-sm dark:bg-surface-2 dark:border-border bg-white border-light-border ' +
  'dark:text-text-primary text-light-text-primary focus:outline-none focus:ring-2 focus:ring-[var(--color-group-green-600)]';
export const btnPrimary =
  'px-3.5 py-1.5 rounded-lg text-xs font-semibold text-white bg-[var(--color-group-green-600)] ' +
  'hover:opacity-90 disabled:opacity-40 disabled:cursor-not-allowed cursor-pointer transition-opacity';
export const btnGhost =
  'px-3.5 py-1.5 rounded-lg text-xs font-medium border dark:border-border border-light-border ' +
  'dark:text-text-secondary text-light-text-secondary dark:hover:bg-surface-2 hover:bg-light-surface-2 ' +
  'disabled:opacity-40 disabled:cursor-not-allowed cursor-pointer transition-colors';
export const btnDanger =
  'px-3.5 py-1.5 rounded-lg text-xs font-semibold text-white bg-red-600 hover:opacity-90 ' +
  'disabled:opacity-40 disabled:cursor-not-allowed cursor-pointer transition-opacity';

export const STATUS_LABEL: Record<string, string> = {
  DRAFT: '草稿',
  REVIEW: '待審核',
  APPROVED: '已核准',
  ACTIVE: '生效中',
  REJECTED: '已退回',
  RETIRED: '已下架',
};

export function errMsg(e: unknown): string {
  if (e instanceof Error) return e.message;
  if (e && typeof e === 'object' && 'message' in e) return String((e as { message: unknown }).message);
  return String(e);
}
