import type { BaseLayoutProps } from 'fumadocs-ui/layouts/shared';
import logo from '@/assets/hoori.svg';
import { appName, gitConfig } from './shared';

export function baseOptions(): BaseLayoutProps {
  return {
    nav: {
      title: (
        <>
          {/* Plain img: next/image optimization is unavailable with output: 'export'. */}
          <img src={logo.src} alt="" width={24} height={24} />
          {appName}
        </>
      ),
    },
    githubUrl: `https://github.com/${gitConfig.user}/${gitConfig.repo}`,
  };
}
