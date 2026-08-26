import React from 'react';
import { clsx } from 'clsx';

interface BadgeProps {
  children: React.ReactNode;
  variant?: 'amber' | 'parchment' | 'gold' | 'dark' | 'success';
  className?: string;
  icon?: React.ReactNode;
}

export const Badge: React.FC<BadgeProps> = ({
  children,
  variant = 'amber',
  className,
  icon,
}) => {
  const variantClasses = {
    amber: 'bg-amber-950/70 text-amber-300 border-amber-600/40',
    parchment: 'bg-amber-100 text-stone-900 border-amber-300',
    gold: 'bg-gradient-to-r from-amber-500/20 to-amber-600/30 text-amber-200 border-amber-400/50 shadow-sm shadow-amber-950/40',
    dark: 'bg-stone-900 text-stone-300 border-stone-800',
    success: 'bg-emerald-950/70 text-emerald-300 border-emerald-600/40',
  };

  return (
    <span
      className={clsx(
        'inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full text-xs font-medium border tracking-wide uppercase',
        variantClasses[variant],
        className
      )}
    >
      {icon}
      <span>{children}</span>
    </span>
  );
};
