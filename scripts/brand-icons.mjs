// Builds brand-icons.json (for the phone app and the desktop popup) from Simple Icons
// (https://simpleicons.org, CC0). Run from the repo root:  node scripts/brand-icons.mjs
//
// Each entry: { s: slug, t: title, h: brand hex color, p: SVG path (24x24 viewBox), a: [aliases] }.
// Icons that Simple Icons lists under a stricter license (GPL, CC-BY-SA, NC, custom, ...) are left
// out, so everything bundled is CC0 / MIT / Apache-2.0 / BSD / Unlicense.
import { execFileSync, execSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";

const VERSION = "16.33.0";
const root = path.resolve(import.meta.dirname, "..");
const work = path.join(root, "target", "simple-icons");
const pkg = path.join(work, "package");
if (!fs.existsSync(path.join(pkg, "package.json")) || JSON.parse(fs.readFileSync(path.join(pkg, "package.json"))).version !== VERSION) {
  fs.rmSync(work, { recursive: true, force: true });
  fs.mkdirSync(work, { recursive: true });
  execSync(`npm pack simple-icons@${VERSION} --silent`, { cwd: work }); // fixed string, no user input
  execFileSync("tar", ["xzf", `simple-icons-${VERSION}.tgz`], { cwd: work });
}

const OK_LICENSES = new Set(["CC0-1.0", "MIT", "Apache-2.0", "BSD-3-Clause", "BSD-2-Clause", "Unlicense"]);
const data = JSON.parse(fs.readFileSync(path.join(pkg, "data", "simple-icons.json"), "utf8"));
const icons = [];
let skipped = 0;
for (const i of data) {
  if (i.license && !OK_LICENSES.has(i.license.type)) {
    skipped++;
    continue;
  }
  const svg = fs.readFileSync(path.join(pkg, "icons", `${i.slug}.svg`), "utf8");
  const d = svg.match(/ d="([^"]+)"/)?.[1];
  if (!d) continue;
  const aliases = [
    ...(i.aliases?.aka ?? []),
    ...(i.aliases?.dup ?? []).map((x) => x.title),
    ...Object.values(i.aliases?.loc ?? {}),
  ];
  icons.push({ s: i.slug, t: i.title, h: i.hex, p: d, ...(aliases.length ? { a: aliases } : {}) });
}

const json = JSON.stringify({ source: `simple-icons ${VERSION} (CC0 and permissive licenses only)`, icons });
const outputs = [
  path.join(root, "android", "app", "src", "main", "assets", "brand-icons.json"),
  path.join(root, "desktop", "ui", "brand-icons.json"),
];
for (const out of outputs) {
  fs.mkdirSync(path.dirname(out), { recursive: true });
  fs.writeFileSync(out, json);
}
console.log(`${icons.length} icons (${skipped} skipped for their license), ${(json.length / 1e6).toFixed(1)} MB`);
