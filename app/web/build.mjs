// Builds the web client with esbuild: `node build.mjs` writes dist/ (hashed bundle names), `--serve` serves a watched
// build, `--test` compiles src/**/*.test.ts and runs them with `node --test`.
import { spawnSync } from 'node:child_process';
import { cpSync, existsSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import * as esbuild from 'esbuild';

const root = dirname(fileURLToPath(import.meta.url));
const dist = join(root, 'dist');
// What `:core:jsBrowserProductionLibraryDistribution` writes; the app imports it as `@sofaking/core`.
const core = resolve(root, '../core/build/dist/js/productionLibrary/sofa-king-core.mjs');
const args = new Set(process.argv.slice(2));
const dev = args.has('--serve');

function sources(dir, suffix) {
    return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
        const path = join(dir, entry.name);
        if (entry.isDirectory()) return sources(path, suffix);
        return entry.name.endsWith(suffix) ? [path] : [];
    });
}

const common = {
    bundle: true,
    format: 'esm',
    target: 'es2022',
    alias: { '@sofaking/core': core },
    // Ktor's WebSocket engine imports `ws` where it runs on Node; a browser never takes that branch.
    external: ['ws'],
    logLevel: 'info',
};

if (args.has('--test')) {
    const out = join(root, 'build', 'test');
    rmSync(out, { recursive: true, force: true });
    const tests = sources(join(root, 'src'), '.test.ts');
    await esbuild.build({
        ...common,
        entryPoints: tests,
        outdir: out,
        outExtension: { '.js': '.mjs' },
        platform: 'node',
        external: ['ws', 'hls.js'],
    });
    const run = spawnSync(process.execPath, ['--test', ...sources(out, '.test.mjs')], { stdio: 'inherit' });
    process.exit(run.status ?? 1);
}

if (!existsSync(core)) {
    console.error(`Missing ${relative(root, core)}: run ./gradlew :core:jsBrowserProductionLibraryDistribution in app/ first.`);
    process.exit(1);
}

const options = {
    ...common,
    entryPoints: { app: join(root, 'src/main.ts') },
    outdir: dist,
    entryNames: dev ? '[name]' : '[name]-[hash]',
    minify: !dev,
    sourcemap: dev ? 'linked' : false,
    metafile: true,
    platform: 'browser',
    loader: { '.css': 'css' },
};

/** Copies public/ and writes index.html with the bundle's file names in place of %JS% and %CSS%. */
function finish(metafile) {
    mkdirSync(dist, { recursive: true });
    cpSync(join(root, 'public'), dist, { recursive: true });
    const names = Object.keys(metafile.outputs).map((path) => path.split('/').pop());
    const js = names.find((name) => name.endsWith('.js'));
    const css = names.find((name) => name.endsWith('.css'));
    const page = readFileSync(join(root, 'public/index.html'), 'utf8').replace('%JS%', js).replace('%CSS%', css);
    writeFileSync(join(dist, 'index.html'), page);
}

rmSync(dist, { recursive: true, force: true });
if (dev) {
    const context = await esbuild.context({
        ...options,
        plugins: [{ name: 'page', setup: (build) => build.onEnd((result) => result.metafile && finish(result.metafile)) }],
    });
    await context.watch();
    const { port } = await context.serve({ servedir: dist, port: 8000 });
    console.log(`Serving http://localhost:${port}/ (set SOFA_CORS_ORIGINS=http://localhost:${port} on the server)`);
} else {
    const result = await esbuild.build(options);
    finish(result.metafile);
}
