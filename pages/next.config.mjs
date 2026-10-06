import { fileURLToPath } from 'node:url';
import { createMDX } from 'fumadocs-mdx/next';

const withMDX = createMDX();

/** @type {import('next').NextConfig} */
const config = {
  output: 'export',
  reactStrictMode: true,
  // Don't generate AGENTS.md/CLAUDE.md here; repository rules live in the root AGENTS.md.
  agentRules: false,
  // The manual includes code from ../examples; let Turbopack read the whole repository.
  turbopack: { root: fileURLToPath(new URL('..', import.meta.url)) },
};

export default withMDX(config);
