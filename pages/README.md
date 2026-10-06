# Hoori Micro docs site

Developer documentation built with [Fumadocs](https://fumadocs.dev) on Next.js (static export).

```bash
npm install
npm run dev      # http://localhost:3000
npm run build    # static site in out/
```

Content lives in `content/docs/` as MDX, split into three sidebar tabs:
`guide/`, `reference/` and `examples/` (order in each `meta.json`).
Available MDX components: `Callout`, `Cards`/`Card`, `Tabs`/`Tab`, `Steps`/`Step`,
`Files`/`Folder`/`File`, `Accordions`/`Accordion`, `TypeTable` (see `components/mdx.tsx`).

Show code from `examples/` with `<include meta='title="File.java"'>../../../../examples/…</include>`
instead of copying it; a moved or deleted file then fails the build.

This manual is the only user-facing documentation (`docs/` in the repository root is internal).
Keep it consistent with the code; do not document APIs that do not exist.
