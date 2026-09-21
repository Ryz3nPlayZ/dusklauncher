import { defineConfig, type Plugin } from "vite";
import react from "@vitejs/plugin-react";
import { createReadStream, existsSync, statSync } from "node:fs";
import { extname, join, normalize, resolve } from "node:path";

/* The browser preview has no jar to read the cosmetics catalog from, so the
   dev server hands out the client mod's source assets at /__cosmetics — the
   same registry.json + PNGs `cosmetics.rs` reads out of the bundled jar. */
const COSMETICS_DIR = resolve(__dirname, "../client-mod/src/main/resources/assets/fasterclient/cosmetics");
const MIME: Record<string, string> = { ".json": "application/json", ".png": "image/png" };

function serveCosmetics(): Plugin {
  return {
    name: "dusk-serve-cosmetics",
    configureServer(server) {
      server.middlewares.use("/__cosmetics", (req, res, next) => {
        const rel = normalize(decodeURIComponent((req.url ?? "/").split("?")[0])).replace(/^([/\\])+/, "");
        const file = join(COSMETICS_DIR, rel);
        if (!file.startsWith(COSMETICS_DIR) || !existsSync(file) || !statSync(file).isFile()) return next();
        res.setHeader("Content-Type", MIME[extname(file)] ?? "application/octet-stream");
        res.setHeader("Cache-Control", "no-cache");
        createReadStream(file).pipe(res);
      });
    },
  };
}

export default defineConfig({
  plugins: [react(), serveCosmetics()],
  clearScreen: false,
  server: { port: 5173, strictPort: true },
  build: { target: "es2021" },
});
