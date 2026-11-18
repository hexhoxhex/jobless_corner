import clsx from "clsx";

/**
 * The app's logo: the ship before the skyline, the same art as the TV app's
 * launcher icon (moviebox-tv/icon-source.png, cut to its rounded tile in
 * public/brand/logo.png).
 */
export function BrandMark({ className }: { className?: string }) {
  return (
    // eslint-disable-next-line @next/next/no-img-element
    <img
      src="/brand/logo.png"
      alt=""
      width={160}
      height={160}
      draggable={false}
      className={clsx("select-none rounded-[22%] shadow-[0_6px_18px_rgba(0,0,0,0.45)] ring-1 ring-white/10", className)}
    />
  );
}
