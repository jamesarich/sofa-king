# Sofa King web client

Plain TypeScript and hls.js, bundled by esbuild, on `core`'s JS library (`SofaKingWeb` in `core/src/jsMain`). The server serves the build at `/app/`.

```sh
(cd .. && ./gradlew :core:jsBrowserProductionLibraryDistribution)
npm ci && npm run build && npm test
```

`npm run dev` serves a watched build at `http://localhost:8000/`; start the server with `SOFA_CORS_ORIGINS=http://localhost:8000`. [Development](../docs/development.md#web-app) has the rest.
