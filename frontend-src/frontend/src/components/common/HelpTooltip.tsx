import { useState } from 'react';

interface Props {
  text: string;
  children?: React.ReactNode;
}

export default function HelpTooltip({ text, children }: Props) {
  const [show, setShow] = useState(false);

  return (
    <span className="relative inline-flex items-center">
      {children}
      <button
        type="button"
        onMouseEnter={() => setShow(true)}
        onMouseLeave={() => setShow(false)}
        onFocus={() => setShow(true)}
        onBlur={() => setShow(false)}
        className="ml-1 w-4 h-4 rounded-full inline-flex items-center justify-center text-[9px] font-bold
          dark:bg-surface-3 dark:text-text-tertiary dark:hover:text-text-secondary
          bg-light-surface-3 text-light-text-tertiary hover:text-light-text-secondary
          transition-colors cursor-help shrink-0"
        aria-label={text}
      >
        ?
      </button>
      {show && (
        <span className="absolute bottom-full left-1/2 -translate-x-1/2 mb-2 z-50
          px-3 py-2 rounded-lg text-[11px] leading-relaxed max-w-[260px] w-max
          dark:bg-surface-3 dark:text-text-secondary dark:border-border
          bg-gray-800 text-white
          border shadow-lg pointer-events-none"
          role="tooltip"
        >
          {text}
          <span className="absolute top-full left-1/2 -translate-x-1/2 -mt-px
            border-4 border-transparent dark:border-t-surface-3 border-t-gray-800" />
        </span>
      )}
    </span>
  );
}
