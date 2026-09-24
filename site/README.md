# SimTether website

Static marketing/docs site, built with [Astro](https://astro.build). No
JavaScript is shipped to visitors — Astro is only a build-time tool.

## Develop

```sh
cd site
npm install
npm run dev      # http://localhost:4321
```

## Build

```sh
npm run build    # static output in site/dist/
npm run preview  # serve the production build locally
```

Any static host works: point it at this directory with build command
`npm run build` and output directory `dist` (Cloudflare Pages and GitHub
Actions both support this natively).

## Internationalization

Routing is Astro's built-in i18n (`astro.config.mjs`):

- `en` is the default locale; all locales are prefixed (`/en/…`, `/tr/…`)
- `/` redirects to `/en/`
- English is canonical — change copy in `src/pages/en/` first, then mirror
  to other locales
- Pages that exist in a locale: defined by `TR_PAGES` in
  `src/layouts/Layout.astro`. Nav/footer links and `hreflang` alternates
  fall back to English automatically for pages a locale doesn't have
- Legal pages (privacy, terms) are English-only by design — the English
  text is the canonical version

To add a locale: add it to `locales` in `astro.config.mjs`, create
`src/pages/<locale>/`, add its `ui` strings + page list in `Layout.astro`.

## Before going live

- `astro.config.mjs` → set `site:` to the production domain (drives
  canonical + hreflang URLs)
- `[DATE]` / `[CONTACT-EMAIL]` placeholders in privacy + terms pages
- `https://github.com/` placeholder links → real repository URL
