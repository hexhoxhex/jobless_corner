/** @type {import('next').NextConfig} */
const nextConfig = {
  // Tauri loads the frontend as static files from `out/`. SSR isn't
  // available because there's no Node runtime in the WebView. The
  // App Router supports `export` mode; pages must be either fully
  // static or use 'use client' for runtime data fetching.
  output: "export",
  // Disable Next.js image optimization — it requires a running Next
  // server. In static export mode, plain <img> works fine for our
  // remote H5 poster URLs (no resizing needed).
  images: { unoptimized: true },
  // Strict mode catches double-render bugs early; cheap insurance.
  reactStrictMode: true,
};
module.exports = nextConfig;
