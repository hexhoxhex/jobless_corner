"use client";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { ensureRelay } from "@/lib/net/relay";

export function Providers({ children }: { children: React.ReactNode }) {
  const [client] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            staleTime: 10 * 60_000,
            gcTime: 30 * 60_000,
            retry: 1,
            refetchOnWindowFocus: false,
          },
        },
      }),
  );
  useEffect(() => {
    ensureRelay().catch((e) => console.error(`relay: ${String(e)}`));
  }, []);
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}
