"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useMemo, useState } from "react";
import clsx from "clsx";
import { useQuery } from "@tanstack/react-query";
import { Bookmark, Clapperboard, Home, PanelLeftClose, PanelLeftOpen, Radio, Search, Tv } from "lucide-react";
import { mergeSchedule, publishedSchedule, siteSchedule } from "@/lib/live/catalog";
import { isLiveNow, playableChannels } from "@/lib/live/homeLive";
import { continueList, useLibrary, type Progress } from "@/lib/store/library";
import { watchHref } from "@/lib/watchHref";
import { BrandMark } from "./BrandMark";
import { TitleBar, usePlatform } from "./TitleBar";

const NAV = [
  { href: "/", label: "Home", icon: Home },
  { href: "/movies", label: "Movies", icon: Clapperboard },
  { href: "/series", label: "Series", icon: Tv },
  { href: "/live", label: "Live TV", icon: Radio },
] as const;

const SIDEBAR_KEY = "sidebar";

/** Collapsed to icons: the viewer's choice once made, else when the window is narrow. */
function useSidebar(): [boolean, () => void] {
  const [pref, setPref] = useState<"open" | "closed" | null>(null);
  const [narrow, setNarrow] = useState(false);
  useEffect(() => {
    try {
      const v = localStorage.getItem(SIDEBAR_KEY);
      if (v === "open" || v === "closed") setPref(v);
    } catch {}
    const mq = window.matchMedia("(max-width: 1100px)");
    const on = () => setNarrow(mq.matches);
    on();
    mq.addEventListener("change", on);
    return () => mq.removeEventListener("change", on);
  }, []);
  const collapsed = pref ? pref === "closed" : narrow;
  const toggle = () => {
    const next = collapsed ? "open" : "closed";
    setPref(next);
    try {
      localStorage.setItem(SIDEBAR_KEY, next);
    } catch {}
  };
  return [collapsed, toggle];
}

/** Matches on right now, from the schedule the Live pages already load (no extra site fetch). */
function useLiveCount(): number {
  const pub = useQuery({ queryKey: ["live-schedule", "published"], queryFn: publishedSchedule, staleTime: 5 * 60_000 });
  // The site's own page is heavy: only use it when another page already has it.
  const site = useQuery({ queryKey: ["live-schedule", "site"], queryFn: siteSchedule, staleTime: 15 * 60_000, enabled: false });
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 60_000);
    return () => clearInterval(t);
  }, []);
  return useMemo(() => {
    if (!pub.data && !site.data) return 0;
    return mergeSchedule(site.data, pub.data).filter((e) => isLiveNow(e, now / 1000) && playableChannels(e).length > 0).length;
  }, [pub.data, site.data, now]);
}

function useMounted() {
  const [m, setM] = useState(false);
  useEffect(() => setM(true), []);
  return m;
}

function NavItem({
  href,
  label,
  icon: Icon,
  active,
  collapsed,
  badge,
}: {
  href: string;
  label: string;
  icon: typeof Home;
  active: boolean;
  collapsed: boolean;
  badge?: React.ReactNode;
}) {
  return (
    <Link
      href={href}
      aria-current={active ? "page" : undefined}
      title={collapsed ? label : undefined}
      className={clsx(
        "group relative flex h-10 items-center rounded-[10px] text-[14px] font-medium transition-colors duration-fast",
        collapsed ? "justify-center" : "gap-3 px-3",
        active ? "bg-accent/[0.13] text-ink" : "text-ink-3 hover:bg-white/[0.05] hover:text-ink-2",
      )}
    >
      <span
        className={clsx(
          "absolute -left-3 top-2 bottom-2 w-[3px] rounded-r-full bg-accent-soft transition-opacity duration-fast",
          active ? "opacity-100" : "opacity-0",
        )}
      />
      <span className="relative">
        <Icon className={clsx("h-[19px] w-[19px] shrink-0", active && "text-accent-soft")} strokeWidth={active ? 2 : 1.75} />
        {collapsed && badge ? <span className="absolute -right-1 -top-1 h-2 w-2 rounded-full bg-live ring-2 ring-[#0c0d10]" /> : null}
      </span>
      {!collapsed && <span className="flex-1 truncate">{label}</span>}
      {!collapsed && badge}
    </Link>
  );
}

function SectionLabel({ children, collapsed, action }: { children: React.ReactNode; collapsed: boolean; action?: React.ReactNode }) {
  if (collapsed) return <div className="mx-auto my-3 h-px w-8 bg-line" />;
  return (
    <div className="mb-1.5 mt-5 flex items-center justify-between px-3">
      <p className="text-[11px] font-semibold uppercase tracking-[0.12em] text-ink-4">{children}</p>
      {action}
    </div>
  );
}

function RecentItem({ p }: { p: Progress }) {
  const pct = p.durationS > 0 ? Math.min(100, (p.positionS / p.durationS) * 100) : 0;
  const left = Math.max(1, Math.round((p.durationS - p.positionS) / 60));
  const sub = p.season ? `S${p.season} E${p.episode} · ${left} min left` : `${left} min left`;
  return (
    <Link
      href={watchHref(p, p.season, p.episode) + "&resume=1"}
      title={`Resume ${p.title}`}
      className="group flex items-center gap-3 rounded-[10px] p-1.5 pr-2 transition-colors duration-fast hover:bg-white/[0.05]"
    >
      <span className="relative h-[52px] w-[35px] shrink-0 overflow-hidden rounded-md bg-surface-2 ring-1 ring-line">
        {p.poster ? (
          // eslint-disable-next-line @next/next/no-img-element
          <img src={p.poster} alt="" loading="lazy" className="h-full w-full object-cover" />
        ) : null}
        <span className="absolute inset-x-0 bottom-0 h-[3px] bg-black/60">
          <span className="block h-full bg-accent" style={{ width: `${pct}%` }} />
        </span>
      </span>
      <span className="min-w-0">
        <span className="block truncate text-[13px] font-medium text-ink-2 group-hover:text-ink">{p.title}</span>
        <span className="block truncate text-xs text-ink-4">{sub}</span>
      </span>
    </Link>
  );
}

function Sidebar({ path }: { path: string }) {
  const [collapsed, toggle] = useSidebar();
  const platform = usePlatform();
  const mounted = useMounted();
  const live = useLiveCount();
  const progress = useLibrary((s) => s.progress);
  const savedCount = useLibrary((s) => Object.keys(s.saved).length);
  const recents = useMemo(() => continueList(progress).slice(0, 5), [progress]);

  // Ctrl+B shows or hides the labels.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && !e.shiftKey && e.key.toLowerCase() === "b") {
        e.preventDefault();
        toggle();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  });

  const isActive = (href: string) => (href === "/" ? path === "/" : path === href || path.startsWith(`${href}/`));

  return (
    <aside
      className={clsx(
        "z-sidebar flex h-full shrink-0 flex-col border-r border-line bg-[#0c0d10]",
        collapsed ? "w-[72px]" : "w-[248px]",
      )}
    >
      {/* The top of the sidebar moves the window too; on macOS the system's buttons sit here. */}
      <div data-tauri-drag-region className={clsx("flex shrink-0 items-center", platform === "mac" ? "h-[96px] pt-7" : "h-[72px]", collapsed ? "justify-center" : "px-4")}>
        <Link href="/" aria-label="Vijana BaruBaru home" className="flex min-w-0 items-center gap-3 rounded-[12px]">
          <BrandMark className="h-10 w-10 shrink-0" />
          {!collapsed && (
            <span className="min-w-0">
              <span className="block truncate font-display text-[16px] font-extrabold leading-5 tracking-tight text-ink">Vijana BaruBaru</span>
              <span className="block truncate text-[11px] font-medium leading-4 text-ink-4">Movies, series and live TV</span>
            </span>
          )}
        </Link>
      </div>

      <div className={clsx("shrink-0 pb-1", collapsed ? "px-3" : "px-3")}>
        <Link
          href="/search"
          title={collapsed ? "Search (Ctrl K)" : "Search (Ctrl K)"}
          className={clsx(
            "flex h-10 items-center rounded-[10px] border text-sm transition-colors duration-fast",
            collapsed ? "justify-center" : "gap-2.5 px-3",
            path === "/search"
              ? "border-accent/40 bg-accent/[0.08] text-ink-2"
              : "border-line bg-surface-2/60 text-ink-4 hover:border-line-strong hover:text-ink-3",
          )}
        >
          <Search className="h-4 w-4 shrink-0" strokeWidth={2} />
          {!collapsed && (
            <>
              <span className="flex-1 truncate">Search</span>
              <kbd className="rounded border border-line px-1.5 font-sans text-[11px] font-medium text-ink-4">Ctrl K</kbd>
            </>
          )}
        </Link>
      </div>

      <div className="scroll-area min-h-0 flex-1 px-3 pb-3">
        <SectionLabel collapsed={collapsed}>Browse</SectionLabel>
        <nav className="flex flex-col gap-0.5" aria-label="Browse">
          {NAV.map((n) => (
            <NavItem
              key={n.href}
              {...n}
              collapsed={collapsed}
              active={isActive(n.href)}
              badge={
                n.href === "/live" && live > 0 ? (
                  <span className="inline-flex items-center gap-1.5 rounded-full bg-live/15 px-2 py-0.5 text-[11px] font-semibold text-[#ff9a9d]" title={`${live} matches on now`}>
                    <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-live" />
                    {live} on now
                  </span>
                ) : undefined
              }
            />
          ))}
        </nav>

        <SectionLabel collapsed={collapsed}>Your library</SectionLabel>
        <nav className="flex flex-col gap-0.5" aria-label="Your library">
          <NavItem
            href="/library"
            label="My list"
            icon={Bookmark}
            collapsed={collapsed}
            active={isActive("/library")}
            badge={mounted && savedCount > 0 ? <span className="tabular text-xs font-medium text-ink-4">{savedCount}</span> : undefined}
          />
        </nav>

        {mounted && !collapsed && recents.length > 0 && (
          <>
            <SectionLabel
              collapsed={collapsed}
              action={
                <Link href="/library" className="text-[11px] font-semibold text-ink-4 transition-colors hover:text-accent-soft">
                  See all
                </Link>
              }
            >
              Recently watched
            </SectionLabel>
            <div className="flex flex-col gap-0.5">
              {recents.map((p) => (
                <RecentItem key={p.key} p={p} />
              ))}
            </div>
          </>
        )}
      </div>

      <div className={clsx("flex h-12 shrink-0 items-center border-t border-line", collapsed ? "justify-center" : "justify-between pl-3 pr-2")}>
        {!collapsed && <span className="tabular text-[11px] text-ink-4">Version 0.1.319</span>}
        <button
          onClick={toggle}
          aria-label={collapsed ? "Expand sidebar" : "Collapse sidebar"}
          title={collapsed ? "Expand sidebar (Ctrl B)" : "Collapse sidebar (Ctrl B)"}
          className="flex h-9 w-9 cursor-pointer items-center justify-center rounded-[10px] text-ink-4 transition-colors duration-fast hover:bg-white/[0.06] hover:text-ink-2"
        >
          {collapsed ? <PanelLeftOpen className="h-[18px] w-[18px]" strokeWidth={1.75} /> : <PanelLeftClose className="h-[18px] w-[18px]" strokeWidth={1.75} />}
        </button>
      </div>
    </aside>
  );
}

export function AppShell({ children }: { children: React.ReactNode }) {
  const path = usePathname();
  const router = useRouter();

  const watching = path === "/watch" || path.startsWith("/live/watch");

  // Ctrl+K or "/" opens search from anywhere outside a text field. On the
  // watch pages they go to the side panel's own search instead.
  useEffect(() => {
    if (watching) return;
    const onKey = (e: KeyboardEvent) => {
      const t = e.target as HTMLElement | null;
      const typing = t && (t.tagName === "INPUT" || t.tagName === "TEXTAREA" || t.isContentEditable);
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "k") {
        e.preventDefault();
        router.push("/search");
      } else if (e.key === "/" && !typing) {
        e.preventDefault();
        router.push("/search");
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [router, watching]);

  // The watch pages lay out their own window (player, side panel).
  if (watching) return <>{children}</>;

  return (
    <div className="flex h-screen w-screen overflow-hidden bg-bg">
      <Sidebar path={path} />
      <div className="flex min-w-0 flex-1 flex-col">
        <TitleBar />
        <main id="main" className="scroll-area relative min-h-0 flex-1">
          {children}
        </main>
      </div>
    </div>
  );
}
