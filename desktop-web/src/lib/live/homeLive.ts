// The Home "Live now" row: the competitions people open the app for, not the
// whole day. Port of the TV app's homeLiveOf (ui/TvSportsScreen.kt): the
// Champions League, Premier League, La Liga, Bundesliga first, the other big
// leagues and UEFA cups after, then top motor racing and esports. When none
// of them is on, the best of the rest.

import { plain, isPlaceholderChannel } from "../text";
import type { LiveEvent } from "./catalog";

export const LIVE_FOR_S = 2.5 * 3600;

const COMPETITIONS: [string[], number][] = [
  [["uefa champions league"], 0],
  [["england - premier league", "premier league"], 1],
  [["spain - la liga", "laliga", "la liga", "copa del rey", "supercopa de espana"], 2],
  [["germany - bundesliga", "bundesliga"], 3],
  [["italy - serie a", "serie a", "france - ligue 1", "ligue 1"], 4],
  [["uefa europa league", "uefa conference league"], 5],
];
const NOT_MAIN = /\bwomen\b|\bu(19|20|21|23)\b|\byouth\b|bundesliga 2|2 bundesliga|xtra time|simulcast|multiview|multi view|\bcaf\b/;
const TOP_RACING = /formula 1|\bf1\b|motogp|moto gp/;
const RACING = /nascar|indycar|motor|rally|superbike|wec\b/;
const ESPORTS = /esports|e-sports|league of legends|counter-strike|cs2|dota|valorant/;

function tier(e: LiveEvent): number | null {
  const t = plain(`${e.category} ${e.title}`).toLowerCase();
  if (NOT_MAIN.test(t)) return null;
  const football = /soccer|football|futbol/.test(t) || COMPETITIONS.some(([keys]) => keys.some((k) => t.includes(k)));
  if (football) return COMPETITIONS.find(([keys]) => keys.some((k) => t.includes(k)))?.[1] ?? null;
  if (TOP_RACING.test(t)) return 6;
  if (RACING.test(t) || ESPORTS.test(t)) return 7;
  return null;
}

export function isLiveNow(e: LiveEvent, nowS = Date.now() / 1000): boolean {
  return e.start != null && e.start <= nowS && nowS - e.start < LIVE_FOR_S;
}

export function playableChannels(e: LiveEvent) {
  return e.channels.filter((c) => !isPlaceholderChannel(c.name));
}

export function homeLive(events: LiveEvent[], nowS = Date.now() / 1000): LiveEvent[] {
  const live = events.filter((e) => isLiveNow(e, nowS) && playableChannels(e).length > 0);
  const picked = live
    .map((e) => [tier(e), e] as const)
    .filter((x): x is readonly [number, LiveEvent] => x[0] != null)
    .sort((a, b) => a[0] - b[0] || (a[1].start ?? 0) - (b[1].start ?? 0))
    .map(([, e]) => e);
  return picked.length ? picked : live.slice(0, 6);
}
