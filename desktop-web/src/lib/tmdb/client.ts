// The catalogue: TMDB, as on the TV app (data/tmdb/). TMDB allows CORS, so
// these go direct, not through the relay.

const BASE = "https://api.themoviedb.org/3";
const IMG = "https://image.tmdb.org/t/p/";
const TOKEN = process.env.NEXT_PUBLIC_TMDB_TOKEN ?? "";

export type Kind = "movie" | "tv";

export type Title = {
  id: number;
  kind: Kind;
  title: string;
  year?: number;
  overview: string;
  poster?: string;
  backdrop?: string;
  rating?: number;
  votes?: number;
  language?: string;
  genreIds: number[];
};

export type CastMember = { id: number; name: string; character?: string; photo?: string };

export type Details = Title & {
  tagline?: string;
  runtime?: number;
  genres: string[];
  certification?: string;
  seasons: { number: number; name: string; episodes: number; poster?: string; year?: number }[];
  cast: CastMember[];
  similar: Title[];
  imdbId?: string;
  logo?: string;
  status?: string;
  networks: string[];
};

export type Episode = {
  number: number;
  season: number;
  name: string;
  overview: string;
  still?: string;
  runtime?: number;
  airDate?: string;
  rating?: number;
};

export const Networks = { NETFLIX: 213, HBO: 49, DISNEY_PLUS: 2739, PRIME: 1024, APPLE_TV: 2552 } as const;

export function img(path: string | null | undefined, size: "w185" | "w300" | "w342" | "w500" | "w780" | "w1280" | "original") {
  return path ? `${IMG}${size}${path}` : undefined;
}

export class TmdbError extends Error {}

async function get<T>(path: string, params: Record<string, string | number | undefined> = {}, signal?: AbortSignal): Promise<T> {
  if (!TOKEN) throw new TmdbError("TMDB token missing from this build");
  const q = new URLSearchParams({ language: "en-US" });
  for (const [k, v] of Object.entries(params)) if (v !== undefined && v !== "") q.set(k, String(v));
  const res = await fetch(`${BASE}${path}?${q}`, {
    headers: { Authorization: `Bearer ${TOKEN}`, Accept: "application/json" },
    signal,
  });
  if (!res.ok) throw new TmdbError(`TMDB ${res.status} on ${path}`);
  return res.json() as Promise<T>;
}

type RawItem = {
  id: number;
  media_type?: string;
  title?: string;
  name?: string;
  release_date?: string;
  first_air_date?: string;
  overview?: string;
  poster_path?: string | null;
  backdrop_path?: string | null;
  vote_average?: number;
  vote_count?: number;
  original_language?: string;
  genre_ids?: number[];
};

function yearOf(d?: string): number | undefined {
  const y = d ? Number(d.slice(0, 4)) : NaN;
  return Number.isFinite(y) && y > 1800 ? y : undefined;
}

export function toTitle(r: RawItem, kind?: Kind): Title | null {
  const k: Kind | undefined = kind ?? (r.media_type === "tv" ? "tv" : r.media_type === "movie" ? "movie" : undefined);
  if (!k) return null;
  const title = (r.title ?? r.name ?? "").trim();
  if (!title) return null;
  return {
    id: r.id,
    kind: k,
    title,
    year: yearOf(r.release_date ?? r.first_air_date),
    overview: r.overview ?? "",
    poster: img(r.poster_path, "w342"),
    backdrop: img(r.backdrop_path, "w1280"),
    rating: r.vote_average || undefined,
    votes: r.vote_count,
    language: r.original_language,
    genreIds: r.genre_ids ?? [],
  };
}

type Page = { results: RawItem[]; total_pages?: number };

function list(page: Page, kind?: Kind): Title[] {
  return page.results.map((r) => toTitle(r, kind)).filter((t): t is Title => !!t && !!t.poster);
}

export const tmdb = {
  trending: async (window: "day" | "week" = "day") => list(await get<Page>(`/trending/all/${window}`)),
  popular: async (kind: Kind, page = 1) =>
    list(await get<Page>(`/${kind}/popular`, { page, region: kind === "movie" ? "US" : undefined }), kind),
  topRated: async (kind: Kind, page = 1) => list(await get<Page>(`/${kind}/top_rated`, { page }), kind),
  nowPlaying: async () => list(await get<Page>(`/movie/now_playing`, { region: "US" }), "movie"),
  onTheAir: async () => list(await get<Page>(`/tv/on_the_air`), "tv"),
  discover: async (
    kind: Kind,
    opts: { network?: number; genre?: number; page?: number; sort?: string; language?: string } = {},
  ) =>
    list(
      await get<Page>(`/discover/${kind}`, {
        with_networks: opts.network,
        with_genres: opts.genre,
        page: opts.page ?? 1,
        sort_by: opts.sort ?? "popularity.desc",
        with_original_language: opts.language,
        "vote_count.gte": opts.sort?.startsWith("vote_average") ? 300 : undefined,
      }),
      kind,
    ),
  genres: async (kind: Kind) => (await get<{ genres: { id: number; name: string }[] }>(`/genre/${kind}/list`)).genres,

  search: async (query: string, signal?: AbortSignal) => {
    const page = await get<Page>(`/search/multi`, { query, include_adult: "false" }, signal);
    return page.results
      .map((r) => toTitle(r))
      .filter((t): t is Title => !!t)
      .sort((a, b) => Number(!!b.poster) - Number(!!a.poster));
  },

  details: async (kind: Kind, id: number): Promise<Details> => {
    type Raw = RawItem & {
      tagline?: string;
      runtime?: number;
      episode_run_time?: number[];
      genres?: { id: number; name: string }[];
      status?: string;
      networks?: { name: string }[];
      seasons?: { season_number: number; name: string; episode_count: number; poster_path?: string; air_date?: string }[];
      credits?: { cast: { id: number; name: string; character?: string; profile_path?: string }[] };
      recommendations?: Page;
      similar?: Page;
      external_ids?: { imdb_id?: string };
      images?: { logos: { file_path: string; iso_639_1?: string; aspect_ratio?: number }[] };
      release_dates?: { results: { iso_3166_1: string; release_dates: { certification: string }[] }[] };
      content_ratings?: { results: { iso_3166_1: string; rating: string }[] };
    };
    const raw = await get<Raw>(`/${kind}/${id}`, {
      append_to_response: `credits,recommendations,external_ids,images,${kind === "movie" ? "release_dates" : "content_ratings"}`,
      include_image_language: "en,null",
    });
    const base = toTitle(raw, kind)!;
    const cert =
      kind === "movie"
        ? raw.release_dates?.results.find((r) => r.iso_3166_1 === "US")?.release_dates.find((d) => d.certification)
            ?.certification
        : raw.content_ratings?.results.find((r) => r.iso_3166_1 === "US")?.rating;
    const logo = raw.images?.logos.find((l) => l.iso_639_1 === "en") ?? raw.images?.logos[0];
    return {
      ...base,
      poster: img(raw.poster_path, "w500"),
      backdrop: img(raw.backdrop_path, "original"),
      tagline: raw.tagline || undefined,
      runtime: raw.runtime || raw.episode_run_time?.[0] || undefined,
      genres: (raw.genres ?? []).map((g) => g.name),
      certification: cert || undefined,
      status: raw.status,
      networks: (raw.networks ?? []).map((n) => n.name),
      seasons: (raw.seasons ?? [])
        .filter((s) => s.season_number > 0 && s.episode_count > 0)
        .map((s) => ({
          number: s.season_number,
          name: s.name,
          episodes: s.episode_count,
          poster: img(s.poster_path, "w185"),
          year: yearOf(s.air_date),
        })),
      cast: (raw.credits?.cast ?? []).slice(0, 18).map((c) => ({
        id: c.id,
        name: c.name,
        character: c.character,
        photo: img(c.profile_path, "w185"),
      })),
      similar: list(raw.recommendations ?? { results: [] }, kind).slice(0, 18),
      imdbId: raw.external_ids?.imdb_id || undefined,
      logo: img(logo?.file_path, "w500"),
    };
  },

  season: async (id: number, season: number): Promise<Episode[]> => {
    type Raw = {
      episodes: {
        episode_number: number;
        season_number: number;
        name: string;
        overview?: string;
        still_path?: string;
        runtime?: number;
        air_date?: string;
        vote_average?: number;
      }[];
    };
    const raw = await get<Raw>(`/tv/${id}/season/${season}`);
    return raw.episodes.map((e) => ({
      number: e.episode_number,
      season: e.season_number,
      name: e.name,
      overview: e.overview ?? "",
      still: img(e.still_path, "w300"),
      runtime: e.runtime || undefined,
      airDate: e.air_date || undefined,
      rating: e.vote_average || undefined,
    }));
  },
};
