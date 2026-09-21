#!/usr/bin/env node
/**
 * Builds the launcher's default modpack (`dusk-essentials.mrpack`) from
 * Modrinth: every mod below is resolved to its newest Fabric build for the
 * target Minecraft version, required dependencies are pulled in recursively,
 * and the result is zipped into src-tauri/resources/modpacks/.
 *
 *   node tools/build-default-pack.mjs
 *
 * Rerun whenever the lineup changes or mods update; commit the new .mrpack.
 * License notes for every entry live in docs/MODPACK.md — keep the two in
 * sync. The FasterClient jar is NOT in the pack: the launcher force-loads it
 * into every Fabric profile at launch (see install_and_launch).
 */
import { execSync } from 'node:child_process';
import { mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const MC = '1.21.11';
const LOADER = 'fabric';
const PACK_NAME = 'Dusk Essentials';
const PACK_VERSION = '1.0.0';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');

/** the lineup — see docs/MODPACK.md for licenses + rationale */
const MODS = [
  // performance core
  'fabric-api',
  'sodium',
  'sodium-extra',
  'reeses-sodium-options',
  'iris',
  'lithium',
  'ferrite-core',
  'immediatelyfast',
  'entityculling',
  'dynamic-fps',
  'krypton',
  'badoptimizations',
  // QoL / utilities (HUD basics — keystrokes, CPS, FPS, armor, combo,
  // toggle-sprint — come from the bundled FasterClient, not the pack)
  'modmenu',
  'zoomify',
  'freelook',
  'gamma-utils',
  'betterf3',
  'chat-heads',
  'shulkerboxtooltip',
  'held-item-info',
  'appleskin',
  'better-ping-display-fabric',
  'dynamiccrosshair',
];

const API = 'https://api.modrinth.com/v2';
const UA = 'DuskLauncher/pack-builder (github.com/dusklauncher)';
const headers = { 'User-Agent': UA };

const projectCache = new Map(); // id → project (slug, title)
const versionCache = new Map(); // slug → version
const picked = new Map(); // project id → { file, version }

async function getJson(url) {
  for (let attempt = 0; ; attempt++) {
    const res = await fetch(url, { headers });
    if (res.status === 429 && attempt < 5) {
      await new Promise((r) => setTimeout(r, 1500 * (attempt + 1)));
      continue;
    }
    if (!res.ok) throw new Error(`${res.status} ${url}`);
    return res.json();
  }
}

async function project(idOrSlug) {
  if (!projectCache.has(idOrSlug)) {
    const p = await getJson(`${API}/project/${idOrSlug}`);
    projectCache.set(p.id, p);
    projectCache.set(p.slug, p);
  }
  return projectCache.get(idOrSlug);
}

/** newest fabric build of `slug` for MC, or null when it doesn't have one */
async function latestVersion(slug) {
  if (versionCache.has(slug)) return versionCache.get(slug);
  const gv = encodeURIComponent(JSON.stringify([MC]));
  const ld = encodeURIComponent(JSON.stringify([LOADER]));
  const versions = await getJson(`${API}/project/${slug}/version?game_versions=${gv}&loaders=${ld}`);
  const v = versions[0] ?? null;
  versionCache.set(slug, v);
  return v;
}

async function include(slug, via) {
  const proj = await project(slug);
  if (picked.has(proj.id)) return;
  const v = await latestVersion(proj.slug);
  if (!v) throw new Error(`${proj.slug} (via ${via}) has no ${LOADER} build for ${MC}`);
  const file = v.files.find((f) => f.primary) ?? v.files[0];
  picked.set(proj.id, { file, version: v });
  console.log(`+ ${proj.title} ${v.version_number}  (${via})`);
  for (const dep of v.dependencies) {
    if (dep.dependency_type !== 'required' || !dep.project_id) continue;
    const depProj = await project(dep.project_id);
    await include(depProj.slug, proj.slug);
  }
}

async function fabricLoaderVersion() {
  const loaders = await getJson('https://meta.fabricmc.net/v2/versions/loader');
  const stable = loaders.find((l) => !l.beta && !l.unstable);
  return stable.version;
}

const loaderVersion = await fabricLoaderVersion();
console.log(`fabric-loader ${loaderVersion}\n`);

for (const slug of MODS) await include(slug, 'lineup');

const files = [...picked.values()].map(({ file, version }) => ({
  path: `mods/${file.filename}`,
  hashes: { sha1: file.hashes.sha1, sha512: file.hashes.sha512 },
  env: { client: 'required', server: 'unsupported' },
  downloads: [file.url],
  fileSize: file.size,
}));

const index = {
  formatVersion: 2,
  game: 'minecraft',
  versionId: PACK_VERSION,
  name: PACK_NAME,
  summary: 'The DuskLauncher default instance: performance core plus PvP/QoL utilities.',
  files,
  dependencies: { minecraft: MC, 'fabric-loader': loaderVersion },
};

const staging = join(root, '.pack-build');
const outDir = join(root, 'launcher/src-tauri/resources/modpacks');
rmSync(staging, { recursive: true, force: true });
mkdirSync(join(staging, 'overrides/mods'), { recursive: true });
mkdirSync(outDir, { recursive: true });
writeFileSync(join(staging, 'modrinth.index.json'), JSON.stringify(index, null, 2));

const out = join(outDir, 'dusk-essentials.mrpack');
execSync(`cd '${staging}' && rm -f '${out}' && zip -q -X '${out}' modrinth.index.json`, {
  stdio: 'inherit',
});
rmSync(staging, { recursive: true, force: true });

console.log(`\n${files.length} files → ${out}`);
console.log(`(includes ${files.length - MODS.length} auto-resolved dependencies)`);
