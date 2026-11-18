"use client";

import { Suspense } from "react";
import { Browse } from "@/components/Browse";

export default function MoviesPage() {
  return (
    <Suspense>
      <Browse kind="movie" />
    </Suspense>
  );
}
