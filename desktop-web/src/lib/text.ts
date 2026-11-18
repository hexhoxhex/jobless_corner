// Emoji and pictographs out of text we show. The live schedule arrives
// decorated (flags, sport balls, a TV set before every show); they read as
// clutter and the app uses none. Display only: matching keeps the raw text.
// Port of the TV app's ui/Glyphs.kt (same ranges).

function isPictograph(cp: number): boolean {
  return (
    (cp >= 0x1f000 && cp <= 0x1faff) || // emoji, regional-indicator flags
    (cp >= 0x2600 && cp <= 0x27bf) || // misc symbols, dingbats
    (cp >= 0x2300 && cp <= 0x23ff) || // technical: media keys, hourglass
    (cp >= 0x2b00 && cp <= 0x2bff) || // arrows-as-icons, star, shapes
    (cp >= 0xe0000 && cp <= 0xe007f) || // tag sequences (England/Scotland flags)
    cp === 0xfe0f ||
    cp === 0xfe0e ||
    cp === 0x200d ||
    cp === 0x20e3
  );
}

export function plain(s: string): string {
  if (!s) return s;
  let out = "";
  let changed = false;
  for (const ch of s) {
    if (isPictograph(ch.codePointAt(0)!)) {
      out += " ";
      changed = true;
    } else out += ch;
  }
  return changed ? out.replace(/\s{2,}/g, " ").replace(/\s+([,.:;|)])/g, "$1").trim() : s;
}

/** A schedule "channel" that is only a note ("Channel Not Listed ... ask in chat"). */
export function isPlaceholderChannel(name: string): boolean {
  return /not listed|justask|in chat/i.test(name);
}
