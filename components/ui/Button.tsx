import React from 'react';
import { clsx } from 'clsx';
import { Loader2 } from 'lucide-react';

interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'primary' | 'secondary' | 'outline' | 'ghost' | 'danger';
  size?: 'sm' | 'md' | 'lg';
  isLoading?: boolean;
  leftIcon?: React.ReactNode;
  rightIcon?: React.ReactNode;
}

export const Button = React.forwardRef<HTMLButtonElement, ButtonProps>(
  (
    {
      children,
      className,
      variant = 'primary',
      size = 'md',
      isLoading = false,
      disabled,
      leftIcon,
      rightIcon,
      ...props
    },
    ref
  ) => {
    const sizeClasses = {
      sm: 'px-3 py-1.5 text-xs tracking-wider gap-1.5',
      md: 'px-5 py-2.5 text-sm tracking-wide gap-2',
      lg: 'px-7 py-3.5 text-base tracking-wide font-medium gap-2.5',
    };

    const variantClasses = {
      primary:
        'bg-gradient-to-r from-amber-600 to-amber-700 text-stone-950 font-semibold hover:from-amber-500 hover:to-amber-600 shadow-lg shadow-amber-950/50 hover:shadow-amber-600/30 border border-amber-400/40 active:scale-[0.98]',
      secondary:
        'bg-stone-800 text-amber-200 hover:bg-stone-700 border border-amber-900/40 hover:border-amber-600/40 shadow-md active:scale-[0.98]',
      outline:
        'bg-transparent text-amber-300 border border-amber-500/40 hover:bg-amber-500/10 hover:border-amber-400 active:scale-[0.98]',
      ghost:
        'bg-transparent text-stone-300 hover:text-amber-300 hover:bg-stone-800/60',
      danger:
        'bg-red-900/60 text-red-200 border border-red-700/50 hover:bg-red-800/80 active:scale-[0.98]',
    };

    return (
      <button
        ref={ref}
        disabled={disabled || isLoading}
        className={clsx(
          'inline-flex items-center justify-center rounded-xl transition-all duration-200 select-none disabled:opacity-50 disabled:pointer-events-none disabled:cursor-not-allowed',
          sizeClasses[size],
          variantClasses[variant],
          className
        )}
        {...props}
      >
        {isLoading ? (
          <Loader2 className="w-4 h-4 animate-spin text-current" />
        ) : (
          leftIcon
        )}
        <span>{children}</span>
        {!isLoading && rightIcon}
      </button>
    );
  }
);

Button.displayName = 'Button';
