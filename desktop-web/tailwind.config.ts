import type { Config } from "tailwindcss";

// The TV app's palette (app/src/main/java/com/moviebox/tv/ui/theme/Theme.kt),
// so the desktop app reads as the same product. Components use these names,
// never raw hex.
const config: Config = {
  // Relative to this file, not the folder the dev server was started from.
  content: { relative: true, files: ["./src/**/*.{ts,tsx}"] },
  theme: {
    extend: {
      colors: {
        bg: "#101114",
        surface: "#191F2B",
        "surface-2": "#22252D",
        "surface-3": "#28292E",
        "surface-4": "#383A40",
        line: "rgba(255,255,255,0.08)",
        "line-strong": "rgba(255,255,255,0.16)",
        outline: "#61656D",
        accent: "#07B84E",
        "accent-hover": "#0AC957",
        "accent-soft": "#2FF58B",
        ink: "#FFFFFF",
        "ink-2": "#EDF0F5",
        "ink-3": "#9BA0A8",
        "ink-4": "#767B85",
        gold: "#F5C518",
        danger: "#E5484D",
        live: "#E5484D",
      },
      fontFamily: {
        sans: ['"Inter Variable"', "system-ui", '"Segoe UI"', "Roboto", "sans-serif"],
        display: ['"Manrope Variable"', '"Inter Variable"', "system-ui", "sans-serif"],
      },
      fontSize: {
        // label / meta / body / title / headline / display
        xs: ["12px", { lineHeight: "16px" }],
        sm: ["13px", { lineHeight: "20px" }],
        base: ["15px", { lineHeight: "24px" }],
        lg: ["18px", { lineHeight: "26px" }],
        xl: ["22px", { lineHeight: "30px" }],
        "2xl": ["28px", { lineHeight: "34px" }],
        "3xl": ["40px", { lineHeight: "44px" }],
        "4xl": ["56px", { lineHeight: "58px" }],
      },
      borderRadius: {
        card: "10px",
        panel: "14px",
      },
      transitionTimingFunction: {
        out: "cubic-bezier(.22,1,.36,1)",
      },
      transitionDuration: {
        fast: "150ms",
        base: "220ms",
      },
      zIndex: {
        sidebar: "20",
        header: "30",
        overlay: "40",
        player: "50",
        toast: "60",
      },
      keyframes: {
        rise: {
          from: { opacity: "0", transform: "translateY(12px)" },
          to: { opacity: "1", transform: "translateY(0)" },
        },
        shimmer: {
          from: { backgroundPosition: "-400px 0" },
          to: { backgroundPosition: "400px 0" },
        },
      },
      animation: {
        rise: "rise 320ms cubic-bezier(.22,1,.36,1) both",
        shimmer: "shimmer 1.4s linear infinite",
      },
    },
  },
  plugins: [],
};

export default config;
