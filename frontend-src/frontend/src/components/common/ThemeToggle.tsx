import type { ReactElement } from 'react';
import type { ThemeMode } from '../../types';

interface Props {
  mode: ThemeMode;
  onCycle: () => void;
}

const icons: Record<ThemeMode, ReactElement> = {
  dark: (
    <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
      <path
        d="M13.36 10.03a6 6 0 0 1-7.39-7.39A6 6 0 1 0 13.36 10.03Z"
        fill="currentColor"
      />
    </svg>
  ),
  light: (
    <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
      <circle cx="8" cy="8" r="3" fill="currentColor" />
      <path
        d="M8 1v2M8 13v2M1 8h2M13 8h2M3.05 3.05l1.41 1.41M11.54 11.54l1.41 1.41M3.05 12.95l1.41-1.41M11.54 4.46l1.41-1.41"
        stroke="currentColor"
        strokeWidth="1.5"
        strokeLinecap="round"
      />
    </svg>
  ),
  system: (
    <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
      <rect x="2" y="3" width="12" height="8" rx="1" stroke="currentColor" strokeWidth="1.5" />
      <path d="M5 14h6M8 11v3" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
    </svg>
  ),
};

const labels: Record<ThemeMode, string> = {
  dark: '深色',
  light: '淺色',
  system: '系統',
};

export default function ThemeToggle({ mode, onCycle }: Props) {
  return (
    <button
      onClick={onCycle}
      className="
        group flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg text-xs font-medium
        transition-all duration-200 cursor-pointer
        dark:text-text-secondary dark:hover:text-text-primary dark:bg-surface-2 dark:hover:bg-surface-3
        text-light-text-secondary hover:text-light-text-primary bg-light-surface-2 hover:bg-light-surface-3
      "
      title={`目前：${labels[mode]}模式，點擊切換`}
    >
      <span className="transition-transform duration-200 group-hover:scale-110">
        {icons[mode]}
      </span>
      <span>{labels[mode]}</span>
    </button>
  );
}
