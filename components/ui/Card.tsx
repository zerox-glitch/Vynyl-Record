import React from 'react';
import { clsx } from 'clsx';

interface CardProps extends React.HTMLAttributes<HTMLDivElement> {
  variant?: 'obsidian' | 'amber' | 'parchment' | 'glass';
  glow?: boolean;
}

export const Card: React.FC<CardProps> = ({
  children,
  className,
  variant = 'obsidian',
  glow = false,
  ...props
}) => {
  const variantClasses = {
    obsidian: 'bg-stone-900/80 border border-stone-800 backdrop-blur-md shadow-xl text-stone-100',
    amber: 'bg-stone-900/90 border border-amber-600/30 backdrop-blur-lg shadow-2xl text-stone-100',
    parchment: 'parchment-card text-stone-900 border border-amber-900/20 shadow-2xl',
    glass: 'bg-white/5 border border-white/10 backdrop-blur-md shadow-2xl text-stone-100',
  };

  return (
    <div
      className={clsx(
        'rounded-2xl transition-all duration-300',
        variantClasses[variant],
        glow && 'hover:border-amber-500/50 hover:shadow-amber-900/20 hover:shadow-2xl',
        className
      )}
      {...props}
    >
      {children}
    </div>
  );
};
