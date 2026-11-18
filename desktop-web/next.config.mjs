import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";
import { PHASE_DEVELOPMENT_SERVER } from "next/constants.js";

const here = path.dirname(fileURLToPath(import.meta.url));

/** The TMDB read token: TMDB_TOKEN in the environment (CI), else the
 *  Android project's local.properties (development). Never in the repo. */
function tmdbToken() {
  if (process.env.TMDB_TOKEN) return process.env.TMDB_TOKEN.trim();
  try {
    const props = readFileSync(path.join(here, "..", "local.properties"), "utf8");
    const line = props.split(/\r?\n/).find((l) => l.startsWith("TMDB_TOKEN="));
    return line ? line.slice("TMDB_TOKEN=".length).trim() : "";
  } catch {
    return "";
  }
}

/** @type {import('next').NextConfig} */
const nextConfig = {
  // Tauri serves the frontend as static files from out/: no server, so no
  // dynamic route segments (ids travel in the query string instead).
  output: "export",
  images: { unoptimized: true },
  reactStrictMode: true,
  env: {
    NEXT_PUBLIC_TMDB_TOKEN: tmdbToken(),
  },
};

// The dev server works in its own folder, so a release build (which uses
// .next and exports to out/) can't pull the scripts out from under it.
export default (phase) => (phase === PHASE_DEVELOPMENT_SERVER ? { ...nextConfig, distDir: ".next-dev" } : nextConfig);
