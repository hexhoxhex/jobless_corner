"use client";

import { Suspense } from "react";
import { Browse } from "@/components/Browse";

export default function SeriesPage() {
  return (
    <Suspense>
      <Browse kind="tv" />
    </Suspense>
  );
}
