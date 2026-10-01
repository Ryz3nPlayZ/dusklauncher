# DuskLauncher site

Static landing page: `index.html`, `styles.css`, `main.js`. No build step.

Preview locally:

    python3 -m http.server 4600 --directory site

Live at https://dusklauncher.vercel.app (Vercel project `dusklauncher`). Redeploy from
this folder with `vercel --prod`. Download links resolve at runtime from the GitHub
releases API and fall back to the releases page if that call fails, so a new release
needs no site change as long as asset names keep their suffixes
(`_aarch64.dmg`, `_x64.dmg`, `_x64-setup.exe`, `_amd64.AppImage`, `_amd64.deb`).

Screenshots in `img/` were captured from the launcher's web preview at 1440x900 @2x.
`og:image` is an absolute URL on the Vercel domain; update it if the site moves.
