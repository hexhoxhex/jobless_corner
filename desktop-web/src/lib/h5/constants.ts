/**
 * Constants shared with the Android :shared/net/Constants.kt and the
 * Compose Desktop :desktop H5Api. A patch to URLs / keys here keeps
 * the desktop-web app aligned with Android.
 *
 * SECRET_KEY_DEFAULT — same signing key. It's not a real secret (it's
 * shipped in every Android APK in the world), so embedding it in the
 * frontend bundle is fine. If we ever rotate it, edit here AND in
 * shared/Crypto.kt at the same time.
 */
export const H5_BASE = "https://h5-api.aoneroom.com";
export const PROXY_BASE = "https://themoviebox.org";
export const PAGE_REFERER = "https://themoviebox.org/";

export const BROWSER_UA =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
  "(KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36";

/** Nairobi timezone — same value that returned the highest-tier
 *  anonymous bearer (atp:3) during reverse engineering. Used in
 *  the X-Client-Info header on every H5 call. */
export const X_CLIENT_INFO = '{"timezone":"Africa/Nairobi"}';

export const SECRET_KEY_DEFAULT = "76iRl07s0xSN9jqmEWAt79EBJZulIQIsV64FZr2O";
export const SECRET_KEY_ALT = "Xqn2nnO41/L92o1iuXhSLHTbXvY4Z5ZZ62m8mSLA";
