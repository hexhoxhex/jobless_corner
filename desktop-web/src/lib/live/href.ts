import type { LiveEvent, ScheduleChannel } from "./catalog";

/** The live player for [c]; from the schedule, the event's other channels
 *  come along so the player can offer them as other feeds. */
export function liveHref(c: { id: string; name: string }, ev?: LiveEvent): string {
  const q = new URLSearchParams({ id: c.id, name: c.name });
  if (ev) {
    q.set("event", ev.title);
    q.set("feeds", JSON.stringify(ev.channels.map((x: ScheduleChannel) => [x.id, x.name])));
  }
  return `/live/watch?${q}`;
}
