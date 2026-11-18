"use client";

import { useEffect, useRef, useState } from "react";
import clsx from "clsx";
import { Check } from "lucide-react";

export type MenuItem = { id: string; label: string; detail?: string; selected?: boolean; disabled?: boolean };

/** An icon button that opens a list above the control bar. */
export function Menu({
  icon,
  label,
  items,
  onPick,
  heading,
}: {
  icon: React.ReactNode;
  label: string;
  heading?: string;
  items: MenuItem[];
  onPick: (id: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const box = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const close = (e: MouseEvent) => {
      if (!box.current?.contains(e.target as Node)) setOpen(false);
    };
    const esc = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.stopPropagation();
        setOpen(false);
      }
    };
    window.addEventListener("mousedown", close);
    window.addEventListener("keydown", esc, true);
    return () => {
      window.removeEventListener("mousedown", close);
      window.removeEventListener("keydown", esc, true);
    };
  }, [open]);

  return (
    <div ref={box} className="relative">
      <button
        aria-label={label}
        title={label}
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen((o) => !o)}
        className={clsx(
          "flex h-10 w-10 cursor-pointer items-center justify-center rounded-full text-ink transition-colors duration-fast hover:bg-white/15",
          open && "bg-white/15",
        )}
      >
        {icon}
      </button>
      {open && (
        <div
          role="menu"
          className="absolute bottom-12 right-0 z-overlay max-h-[min(60vh,var(--menu-max,60vh))] min-w-[220px] animate-rise overflow-y-auto rounded-panel border border-line bg-[#15171c]/95 p-1.5 shadow-2xl backdrop-blur-xl"
        >
          {heading && <p className="px-3 pb-1.5 pt-2 text-[11px] font-semibold uppercase tracking-[0.12em] text-ink-4">{heading}</p>}
          {items.length === 0 && <p className="px-3 py-2 text-sm text-ink-4">Nothing to choose</p>}
          {items.map((it) => (
            <button
              key={it.id}
              role="menuitemradio"
              aria-checked={!!it.selected}
              disabled={it.disabled}
              onClick={() => {
                onPick(it.id);
                setOpen(false);
              }}
              className="flex w-full cursor-pointer items-center gap-3 rounded-lg px-3 py-2 text-left text-sm text-ink-2 transition-colors hover:bg-white/10 hover:text-ink disabled:cursor-not-allowed disabled:opacity-40"
            >
              <span className="flex h-4 w-4 shrink-0 items-center justify-center">
                {it.selected && <Check className="h-4 w-4 text-accent-soft" />}
              </span>
              <span className="flex-1">{it.label}</span>
              {it.detail && <span className="text-xs text-ink-4">{it.detail}</span>}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
