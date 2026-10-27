"use client";

import { useState } from "react";
import { ApiError } from "@/lib/api";
import { useReview } from "@/hooks/useOrders";
import type { Review } from "@/types/contract";

export function Stars({ value }: { value: number }) {
  return (
    <span className="text-amber-500" aria-label={`${value} out of 5 stars`}>
      {"★".repeat(value)}
      <span className="text-slate-300">{"★".repeat(5 - value)}</span>
    </span>
  );
}

/** Shown on completed orders: the saved review, or a 1–5 star form. */
export function ReviewForm({ orderId, review }: { orderId: string; review: Review | null }) {
  const [rating, setRating] = useState(0);
  const [hover, setHover] = useState(0);
  const [comment, setComment] = useState("");
  const submit = useReview();

  if (review) {
    return (
      <div className="space-y-1">
        <Stars value={review.rating} />
        {review.comment && <p className="text-sm text-slate-700">{review.comment}</p>}
        <p className="text-xs text-slate-500">Thanks for your feedback.</p>
      </div>
    );
  }

  const code = submit.error instanceof ApiError ? submit.error.code : null;

  return (
    <form
      className="space-y-3"
      onSubmit={(e) => {
        e.preventDefault();
        if (rating) submit.mutate({ id: orderId, rating, comment: comment.trim() });
      }}
    >
      <div className="flex gap-1" onMouseLeave={() => setHover(0)}>
        {[1, 2, 3, 4, 5].map((n) => (
          <button
            key={n}
            type="button"
            aria-label={`${n} star${n > 1 ? "s" : ""}`}
            onMouseEnter={() => setHover(n)}
            onClick={() => setRating(n)}
            className={`text-2xl leading-none ${n <= (hover || rating) ? "text-amber-500" : "text-slate-300"}`}
          >
            ★
          </button>
        ))}
      </div>
      <textarea
        value={comment}
        onChange={(e) => setComment(e.target.value)}
        maxLength={500}
        rows={3}
        placeholder="How was your delivery? (optional)"
        className="w-full rounded-md border border-slate-300 px-3 py-2 text-sm"
      />
      <div className="flex items-center gap-3">
        <button
          type="submit"
          disabled={!rating || submit.isPending}
          className="rounded-md bg-slate-900 px-4 py-2 text-sm text-white disabled:opacity-40"
        >
          {submit.isPending ? "Submitting…" : "Submit review"}
        </button>
        {code === "ALREADY_REVIEWED" && <span className="text-sm text-slate-600">Already reviewed.</span>}
        {submit.error && code !== "ALREADY_REVIEWED" && (
          <span className="text-sm text-rose-600">Could not save the review. Try again.</span>
        )}
      </div>
    </form>
  );
}
