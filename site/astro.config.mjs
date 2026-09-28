import { defineConfig } from 'astro/config';

export default defineConfig({
  // Fly.io static host — swap to the real domain when it's picked.
  // site drives canonical + hreflang URLs.
  site: 'https://simtether-site.fly.dev',
  i18n: {
    defaultLocale: 'en',
    locales: ['en', 'tr', 'ar'],
    routing: {
      prefixDefaultLocale: true,
    },
  },
});
