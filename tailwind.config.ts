import type { Config } from "tailwindcss";

const config: Config = {
  content: [
    "./pages/**/*.{js,ts,jsx,tsx,mdx}",
    "./components/**/*.{js,ts,jsx,tsx,mdx}",
    "./app/**/*.{js,ts,jsx,tsx,mdx}",
  ],
  darkMode: "class",
  theme: {
    extend: {
      colors: {
        obsidian: {
          950: "#070605",
          900: "#0c0a09",
          850: "#141210",
          800: "#1c1917",
          700: "#292524",
          600: "#44403c",
        },
        amber: {
          50: "#fffbeb",
          100: "#fef3c7",
          200: "#fde68a",
          300: "#fcd34d",
          400: "#fbbf24",
          500: "#f59e0b",
          600: "#d97706",
          700: "#b45309",
          800: "#92400e",
          900: "#78350f",
          950: "#451a03",
        },
        parchment: {
          50: "#fdfbf7",
          100: "#faf4ea",
          200: "#f5e8cf",
          300: "#eed5ad",
          400: "#e3bc84",
          500: "#d8a35e",
          600: "#ca8747",
          700: "#a86839",
          800: "#865333",
          900: "#6e452e",
        },
        brass: {
          300: "#f4dc9e",
          400: "#e8c56e",
          500: "#d1a842",
          600: "#b5872d",
          700: "#8f6520",
        },
      },
      fontFamily: {
        serif: ["var(--font-playfair)", "Georgia", "serif"],
        sans: ["var(--font-inter)", "system-ui", "sans-serif"],
        mono: ["var(--font-mono)", "monospace"],
      },
      animation: {
        "spin-slow": "spin 20s linear infinite",
        "spin-vinyl": "spin 1.8s linear infinite",
        "pulse-glow": "pulseGlow 2s cubic-bezier(0.4, 0, 0.6, 1) infinite",
        "float": "float 6s ease-in-out infinite",
      },
      keyframes: {
        pulseGlow: {
          "0%, 100%": { opacity: "0.4", transform: "scale(1)" },
          "50%": { opacity: "0.8", transform: "scale(1.05)" },
        },
        float: {
          "0%, 100%": { transform: "translateY(0px)" },
          "50%": { transform: "translateY(-10px)" },
        },
      },
      backgroundImage: {
        "radial-amber": "radial-gradient(circle at 50% 50%, rgba(217, 119, 6, 0.15), transparent 70%)",
        "radial-spotlight": "radial-gradient(circle at 50% 0%, rgba(251, 191, 36, 0.2), transparent 60%)",
      },
    },
  },
  plugins: [],
};
export default config;
