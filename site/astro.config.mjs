import { defineConfig } from 'astro/config';

export default defineConfig({
  // TODO: set to the production domain before launch (used for canonical + hreflang URLs)
  site: 'https://example.com',
  redirects: {
    '/': '/en/',
  },
  i18n: {
    defaultLocale: 'en',
    locales: ['en', 'tr'],
    routing: {
      prefixDefaultLocale: true,
    },
  },
});
