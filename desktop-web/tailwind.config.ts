import type { Config } from "tailwindcss";

// MovieWay-dark palette — matches Android :app's theme.kt + remote.css
// so a user opening the desktop app feels the same brand they get on
// the TV / phone. Single source of truth for color tokens here; CSS
// vars below mirror them so non-Tailwind components can pick them up.
const config: Config = {
  content: ["./src/**/*.{ts,tsx}"],
  darkMode: "class",
  theme: {
    extend: {
      colors: {
        bg:               "#101114", // deepest base
        surface:          "#191F2B", // primary surface
        "surface-2":      "#28292E", // elevated card
        "surface-3":      "#383A40", // hover / focus tile
        outline:          "#61656D",
        accent:           "#07B84E", // brand green (CTA, focus)
        "accent-soft":    "#2FF58B", // hover state of accent
        "text-primary":   "#FFFFFF",
        "text-secondary": "#EDF0F5",
        "text-muted":     "#9BA0A8",
        "text-hint":      "#767B85",
        gold:             "#F5C518", // IMDb-style rating
        danger:           "#E5484D",
      },
      fontFamily: {
        // System stack — matches Android default + macOS/Windows native.
        // Custom brand font (if we add one) goes here later.
        sans: ['system-ui', '-apple-system', 'BlinkMacSystemFont', '"Segoe UI"', 'Roboto', 'sans-serif'],
      },
      borderRadius: {
        // Cards/posters/buttons use the same radius scale across all
        // surfaces. 12px is the Android default.
        card: "12px",
        pill: "999px",
      },
    },
  },
  plugins: [],
};

export default config;
