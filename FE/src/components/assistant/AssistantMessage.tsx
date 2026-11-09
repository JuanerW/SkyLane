"use client";

import { memo } from "react";
import Markdown, { type Components } from "react-markdown";
import remarkGfm from "remark-gfm";

const components: Components = {
  table: ({ children }) => (
    <div className="assistant-table-scroll" role="region" aria-label="Comparison table" tabIndex={0}>
      <table>{children}</table>
    </div>
  ),
  th: ({ children, style }) => <th scope="col" style={style}>{children}</th>,
  a: ({ href, children }) => href
    ? <a href={href} target="_blank" rel="noopener noreferrer">{children}</a>
    : <span>{children}</span>,
  // Model-generated image URLs are not fetched automatically.
  img: ({ alt }) => <span>{alt || "Image"}</span>,
};

const plugins = [remarkGfm];

/** One safe renderer for both saved messages and incremental assistant text. */
export const AssistantMessage = memo(function AssistantMessage({ text }: { text: string }) {
  return (
    <div className="assistant-markdown">
      <Markdown remarkPlugins={plugins} components={components} skipHtml>{text}</Markdown>
    </div>
  );
});
