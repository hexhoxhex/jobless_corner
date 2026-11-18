"use client";

import { useQuery } from "@tanstack/react-query";
import { tmdb } from "./client";

/** Genre id -> name for films and series together. */
export function useGenreNames(): (id: number) => string | undefined {
  const { data } = useQuery({
    queryKey: ["genres"],
    queryFn: async () => {
      const [m, t] = await Promise.all([tmdb.genres("movie"), tmdb.genres("tv")]);
      const map = new Map<number, string>();
      for (const g of [...m, ...t]) map.set(g.id, g.name);
      return map;
    },
    staleTime: 24 * 3600_000,
  });
  return (id) => data?.get(id);
}
