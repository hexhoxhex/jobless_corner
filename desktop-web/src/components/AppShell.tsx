"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useMemo, useState } from "react";
import clsx from "clsx";
import { useQuery } from "@tanstack/react-query";
import { Bookmark, Clapperboard, Home, PanelLeftClose, PanelLeftOpen, Radio, Tv } from "lucide-react";
import { mergeSchedule, publishedSchedule, siteSchedule } from "@/lib/live/catalog";
import { isLiveNow, playableChannels } from "@/lib/live/homeLive";
import { continueList, useLibrary, type Progress } from "@/lib/store/library";
import { watchHref } from "@/lib/watchHref";
import { BrandMark } from "./BrandMark";
import { TitleBar, usePlatform } from "./TitleBar";
import { FOCUS_SEARCH, hasSectionBar } from "./SectionSearch";

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

function Divider({ collapsed }: { collapsed: boolean }) {
  return <div className={clsx("my-3 h-px bg-line", collapsed ? "mx-auto w-8" : "mx-3")} />;
}

function SectionLabel({ children, action }: { children: React.ReactNode; action?: React.ReactNode }) {
  return (
    <div className="mb-1 flex items-center justify-between px-3">
      <p className="text-[13px] font-semibold text-ink-3">{children}</p>
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
          {!collapsed && <span className="truncate font-display text-[16px] font-extrabold tracking-tight text-ink">Vijana BaruBaru</span>}
        </Link>
      </div>

      <div className="scroll-area min-h-0 flex-1 px-3 pb-3">
        <nav className="flex flex-col gap-0.5 pt-1" aria-label="Browse">
          {NAV.map((n) => (
            <NavItem
              key={n.href}
              {...n}
              collapsed={collapsed}
              active={isActive(n.href)}
              badge={
                n.href === "/live" && live > 0 ? (
                  <span className="inline-flex items-center gap-1.5 tabular text-xs font-medium text-ink-3" title={`${live} matches on now`}>
                    <span className="h-1.5 w-1.5 rounded-full bg-live" />
                    {live}
                  </span>
                ) : undefined
              }
            />
          ))}
        </nav>

        <Divider collapsed={collapsed} />
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
            <Divider collapsed={collapsed} />
            <SectionLabel
              action={
                <Link href="/library" className="text-xs font-medium text-ink-4 transition-colors hover:text-ink-2">
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

      <div className={clsx("flex h-12 shrink-0 items-center", collapsed ? "justify-center" : "justify-end pr-2")}>
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

  // Ctrl+K or "/" (outside a text field) go to the search bar of the section
  // in view; pages without one (a title's page) go to Home's. On the watch
  // pages they go to the side panel's own search instead.
  useEffect(() => {
    if (watching) return;
    const onKey = (e: KeyboardEvent) => {
      const t = e.target as HTMLElement | null;
      const typing = t && (t.tagName === "INPUT" || t.tagName === "TEXTAREA" || t.isContentEditable);
      const wants = ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "k") || (e.key === "/" && !typing);
      if (!wants) return;
      e.preventDefault();
      if (hasSectionBar()) window.dispatchEvent(new Event(FOCUS_SEARCH));
      else router.push("/?focus=1");
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
