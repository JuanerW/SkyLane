"use client";

import { useEffect, useState } from "react";
import { useAddressSearch } from "@/hooks/useAddressSearch";
import { MIN_QUERY_LENGTH } from "@/lib/photon";
import type { Place } from "@/types/contract";

interface Props {
  id: string;
  label: string;
  placeholder: string;
  value: Place | null;
  onChange: (place: Place | null) => void;
  error?: string;
}

/** Only an address picked from the list counts: a place needs coordinates for the quote. */
export function AddressAutocomplete({ id, label, placeholder, value, onChange, error }: Props) {
  const [text, setText] = useState(value?.text ?? "");
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(-1);
  const search = useAddressSearch(open ? text : "");
  const options = search.data ?? [];
  const showList = open && text.trim().length >= MIN_QUERY_LENGTH;

  // Clear form resets the value from outside.
  useEffect(() => {
    if (value === null) setText((t) => (open ? t : ""));
    else setText(value.text);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value]);

  const pick = (p: Place) => {
    onChange(p);
    setText(p.text);
    setOpen(false);
    setActive(-1);
  };

  const onKeyDown = (e: React.KeyboardEvent) => {
    if (!showList || options.length === 0) return;
    if (e.key === "ArrowDown" || e.key === "ArrowUp") {
      e.preventDefault();
      const step = e.key === "ArrowDown" ? 1 : -1;
      setActive((i) => (i + step + options.length) % options.length);
    } else if (e.key === "Enter" && active >= 0) {
      e.preventDefault();
      pick(options[active]);
    } else if (e.key === "Escape") {
      setOpen(false);
    }
  };

  return (
    <div className="relative">
      <label className="lbl" htmlFor={id}>
        {label}
      </label>
      <input
        id={id}
        className={`inp ${error ? "bad" : ""}`}
        autoComplete="off"
        role="combobox"
        aria-expanded={showList}
        aria-controls={`${id}-list`}
        aria-invalid={!!error}
        placeholder={placeholder}
        value={text}
        onChange={(e) => {
          setText(e.target.value);
          setOpen(true);
          setActive(-1);
          if (value) onChange(null);
        }}
        onFocus={() => setOpen(true)}
        onBlur={() => setOpen(false)}
        onKeyDown={onKeyDown}
      />
      {error && <div className="err">{error}</div>}
      {showList && (
        <div
          id={`${id}-list`}
          role="listbox"
          className="absolute inset-x-0 top-[78px] z-10 overflow-hidden rounded-[10px] border border-[#CDD1DB] bg-white shadow-[0_8px_24px_rgba(20,23,31,.12)]"
        >
          {options.map((p, i) => (
            <button
              key={p.text}
              type="button"
              role="option"
              aria-selected={i === active}
              className={`sug ${i === active ? "on" : ""}`}
              // Keep focus so onBlur does not close the list before the click lands.
              onMouseDown={(e) => e.preventDefault()}
              onClick={() => pick(p)}
            >
              {p.text}
            </button>
          ))}
          {search.isFetching && options.length === 0 && <div className="px-3 py-2.5 text-sm text-[#5B6274]">Searching…</div>}
          {search.isError && (
            <div className="px-3 py-2.5 text-sm text-[#B42318]">
              Address search is unavailable.{" "}
              <button
                type="button"
                className="underline"
                onMouseDown={(e) => e.preventDefault()}
                onClick={() => search.refetch()}
              >
                Retry
              </button>
            </div>
          )}
          {search.isSuccess && !search.isFetching && options.length === 0 && (
            <div className="px-3 py-2.5 text-sm text-[#5B6274]">No matching address in San Francisco.</div>
          )}
        </div>
      )}
    </div>
  );
}
