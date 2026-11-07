import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from "react";
import { Box } from "@mui/material";
import { useColorScheme } from "@mui/material/styles";

/**
 * A ```` ```mermaid ```` fence, drawn as a diagram. `Markdown.tsx` swaps this
 * in for the `<pre>` of any code block tagged `mermaid`.
 *
 * **Mermaid is a chunk, like KaTeX.** It is well over a megabyte, so it is
 * reached only through the `import()` below, which only runs once a mermaid
 * block has actually mounted. Nothing may import `mermaid` statically -- that
 * would fold it back into the main bundle with no visible symptom.
 *
 * **`securityLevel: "strict"`** has mermaid sanitise labels and disables click
 * handlers, which keeps the "raw HTML is not rendered" rule true for diagrams
 * too: a label of `<script>` stays text. Mermaid treats `securityLevel` as a
 * secure key, so an `%%{init}%%` directive inside a body cannot loosen it.
 *
 * **`mermaid.render`, never `mermaid.run`.** `render` takes source and hands
 * back an SVG string; it never scans or rewrites the page. It does draw into a
 * temporary element on `document.body` first, and on a bad diagram it would
 * leave an error SVG there -- `suppressErrorRendering` makes it throw instead,
 * and the `finally` below removes the temporaries regardless.
 */
type Mermaid = (typeof import("mermaid"))["default"];

let mermaidPromise: Promise<Mermaid> | null = null;

function loadMermaid(): Promise<Mermaid> {
  mermaidPromise ??= import("mermaid").then((module) => module.default);
  // A failed fetch is not cached, so the next diagram gets a fresh attempt
  // rather than inheriting the rejection forever.
  mermaidPromise.catch(() => {
    mermaidPromise = null;
  });
  return mermaidPromise;
}

type MermaidTheme = "default" | "dark";

let sequence = 0;
// Mermaid's configuration is global, so a render is "initialize, then
// render" as one step, and the steps are queued: two diagrams rendering at
// once must not have one's `initialize` land between the other's two calls.
let queue: Promise<unknown> = Promise.resolve();

function renderDiagram(source: string, theme: MermaidTheme): Promise<string> {
  const job = queue.then(async () => {
    const mermaid = await loadMermaid();
    mermaid.initialize({
      startOnLoad: false,
      securityLevel: "strict",
      suppressErrorRendering: true,
      theme,
    });
    // Unique per render, not per diagram: a theme change renders again, and
    // the id scopes the SVG's own <style> rules.
    const id = `mermaid-diagram-${++sequence}`;
    try {
      const { svg } = await mermaid.render(id, source);
      return svg;
    } finally {
      // Mermaid's scratch wrapper is `d<id>`; the SVG inside it is `<id>`.
      // Both are on <body>, not in our tree, until removed here.
      document.getElementById(`d${id}`)?.remove();
      document.getElementById(id)?.remove();
    }
  });
  queue = job.catch(() => undefined);
  return job;
}

/** The first line of mermaid's error -- its parser errors run to several. */
function errorSummary(error: unknown): string {
  const message = error instanceof Error ? error.message : String(error);
  return message.split("\n")[0].slice(0, 200);
}

interface Props {
  /** The diagram source, exactly as fenced. */
  source: string;
  /** The ordinary code block for the same fence, shown until the SVG is
   *  ready and kept if it never is. */
  fallback: ReactNode;
}

function MermaidDiagram({ source, fallback }: Props) {
  // The per-post override goes through MUI's own state (see `usePostTheme`),
  // so this follows a post's theme as well as the reader's. Rain is a dark
  // scheme. Undefined until MUI has read storage, and nothing renders until
  // then rather than drawing in a theme about to be replaced.
  const { colorScheme } = useColorScheme();
  const theme: MermaidTheme | undefined =
    colorScheme === undefined ? undefined : colorScheme === "light" ? "default" : "dark";

  const [svg, setSvg] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const box = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!theme) return;
    let live = true;
    renderDiagram(source, theme).then(
      (result) => {
        if (!live) return;
        setSvg(result);
        setError(null);
      },
      (reason: unknown) => {
        if (!live) return;
        setSvg(null);
        setError(errorSummary(reason));
      },
    );
    // The previous SVG stays up while a new theme renders, so a toggle swaps
    // one diagram for the other instead of flashing the source in between.
    return () => {
      live = false;
    };
  }, [source, theme]);

  // Mermaid sizes its SVG to fill the column (`width="100%"` plus an inline
  // max-width), which shrinks a wide diagram to unreadable on a phone. Pin it
  // to its natural width instead, so it pans inside its own box like a <pre>.
  useLayoutEffect(() => {
    const element = box.current?.querySelector("svg");
    const width = element?.viewBox.baseVal?.width;
    if (!element || !width) return;
    element.style.maxWidth = "none";
    element.style.width = `${width}px`;
    element.style.height = "auto";
  }, [svg]);

  if (!svg) {
    return (
      <>
        {fallback}
        {error && (
          <Box
            component="p"
            role="note"
            sx={{ mt: -1, mb: 2, fontSize: "13px", color: "text.secondary" }}
          >
            Diagram could not be rendered: {error}
          </Box>
        )}
      </>
    );
  }

  return (
    <Box
      ref={box}
      sx={{
        bgcolor: "background.paper",
        border: "1px solid",
        borderColor: "divider",
        borderRadius: 1,
        p: 2,
        my: 2,
        // Nothing may widen the page: a wide diagram pans in here.
        overflowX: "auto",
        "& svg": { display: "block", mx: "auto" },
      }}
      // Mermaid's own output under `securityLevel: "strict"`, which it runs
      // through DOMPurify -- not anything taken from the body as HTML.
      dangerouslySetInnerHTML={{ __html: svg }}
    />
  );
}

export default MermaidDiagram;
