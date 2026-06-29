/** A single subject (movie or series episode). Matches the H5
 *  /home and /search response shape after our parser pulls the
 *  fields we care about. */
export interface H5Item {
  subjectId: string;
  title: string;
  /** 0 = movie, 1 = TV series, others = music/short/extras */
  type: number;
  year: number | null;
  coverUrl: string;
  rating: number | null;
}

/** A row on the Home screen: a labeled bucket of items. */
export interface H5Row {
  title: string;
  items: H5Item[];
}

/** Detail page payload. */
export interface H5Detail {
  subjectId: string;
  title: string;
  description: string;
  seasons: H5Season[];
  type: number;
}

export interface H5Season {
  season: number;
  episodes: number;
}

export interface H5Stream {
  url: string;
  resolution: number;
  /** e.g. "mp4", "hls". Falls back to "Auto" when absent. */
  format: string;
}

export interface H5Play {
  streams: H5Stream[];
}
