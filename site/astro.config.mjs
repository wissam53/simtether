import { defineConfig } from 'astro/config';

export default defineConfig({
  // Cloudflare Pages — served at the apex, no subpath. site drives
  // canonical + hreflang URLs.
  site: 'https://simtether.pages.dev',
  i18n: {
    defaultLocale: 'en',
    locales: ['en', 'tr', 'ar'],
    routing: {
      prefixDefaultLocale: true,
    },
  },
});
