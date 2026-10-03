import { STATUS_LABEL } from './ui';

const COLORS: Record<string, string> = {
  DRAFT: 'bg-zinc-500/15 text-zinc-500',
  REVIEW: 'bg-amber-500/15 text-amber-600',
  APPROVED: 'bg-sky-500/15 text-sky-600',
  ACTIVE: 'bg-emerald-500/15 text-emerald-600',
  REJECTED: 'bg-red-500/15 text-red-600',
  RETIRED: 'bg-zinc-500/10 text-zinc-400',
};

export default function StatusBadge({ status }: { status: string }) {
  return (
    <span className={`inline-block px-2 py-0.5 rounded-full text-[10px] font-semibold ${COLORS[status] ?? COLORS.DRAFT}`}>
      {STATUS_LABEL[status] ?? status}
    </span>
  );
}
