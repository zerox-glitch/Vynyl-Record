import React from 'react';
import { clsx } from 'clsx';

interface SliderProps {
  value: number; // 0 to 1 or 0 to 100
  onChange: (value: number) => void;
  min?: number;
  max?: number;
  step?: number;
  label?: string;
  displayValue?: string;
  disabled?: boolean;
  className?: string;
}

export const Slider: React.FC<SliderProps> = ({
  value,
  onChange,
  min = 0,
  max = 100,
  step = 1,
  label,
  displayValue,
  disabled = false,
  className,
}) => {
  const percentage = ((value - min) / (max - min)) * 100;

  return (
    <div className={clsx('w-full space-y-2', className)}>
      {(label || displayValue) && (
        <div className="flex justify-between items-center text-xs">
          {label && <span className="text-stone-300 font-medium">{label}</span>}
          {displayValue && (
            <span className="text-amber-400 font-mono font-semibold">
              {displayValue}
            </span>
          )}
        </div>
      )}
      <div className="relative flex items-center h-5">
        <input
          type="range"
          min={min}
          max={max}
          step={step}
          value={value}
          disabled={disabled}
          onChange={(e) => onChange(parseFloat(e.target.value))}
          className="w-full h-1.5 bg-stone-800 rounded-lg appearance-none cursor-pointer accent-amber-500 disabled:opacity-40 disabled:cursor-not-allowed focus:outline-none"
        />
        {/* Track highlight */}
        <div
          className="absolute left-0 h-1.5 bg-gradient-to-r from-amber-700 to-amber-500 rounded-lg pointer-events-none"
          style={{ width: `${percentage}%` }}
        />
      </div>
    </div>
  );
};
