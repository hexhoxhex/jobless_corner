import "./globals.css";
import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "Vijana BaruBaru",
  description: "Live TV, movies, and series on your desktop.",
};

/**
 * Root layout. Tailwind + globals.css set the dark MovieWay palette
 * everywhere; the body wrapper centralises the fixed viewport sizing
 * Tauri's WebView expects (no Next.js-style server-side viewport meta).
 */
export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en" className="dark">
      <body className="bg-bg text-text-primary min-h-screen">
        {children}
      </body>
    </html>
  );
}
