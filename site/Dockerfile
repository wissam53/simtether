# Static site for SimTether — build `site/dist` first: npm run build
# Then deploy from this directory (fly deploy / docker build).
FROM pierrezemb/gostatic
COPY ./dist /srv/http
EXPOSE 8043
