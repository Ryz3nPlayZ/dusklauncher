(() => {
  const REPO = 'Ryz3nPlayZ/dusklauncher';
  const calm = matchMedia('(prefers-reduced-motion: reduce)').matches;

  /* ---------- Nav hairline once the page moves ---------- */
  const nav = document.querySelector('.nav');
  const stuck = () => nav.classList.toggle('is-stuck', scrollY > 8);
  stuck();
  addEventListener('scroll', stuck, { passive: true });

  /* ---------- The sun sets as you scroll past the hero ---------- */
  const sun = document.querySelector('[data-sun]');
  if (sun && !calm) {
    let ticking = false;
    const set = () => {
      ticking = false;
      const k = Math.min(1, scrollY / (innerHeight * 1.1));
      sun.style.transform = `translateY(${k * 220}px) scale(${1 - k * 0.12})`;
      sun.style.opacity = String(0.9 * (1 - k * 0.85));
    };
    addEventListener('scroll', () => {
      if (!ticking) { ticking = true; requestAnimationFrame(set); }
    }, { passive: true });
    set();
  }

  /* ---------- Keystrokes demo ---------- */
  const stage = document.getElementById('keystrokes');
  const keyEls = {};
  stage.querySelectorAll('[data-key]').forEach((el) => { keyEls[el.dataset.key] = el; });
  const clicks = { 0: [], 2: [] };
  if (matchMedia('(pointer: coarse)').matches) {
    stage.querySelector('.hud__hint').textContent = 'Try it: tap LMB and RMB as fast as you can.';
  }
  const cpsEls = { 0: stage.querySelector('[data-cps="0"]'), 2: stage.querySelector('[data-cps="2"]') };

  const typing = (e) => e.target.closest('input, textarea, [contenteditable], summary, button, a');

  document.addEventListener('keydown', (e) => {
    const k = e.key.toLowerCase();
    if (!(k in keyEls) || e.metaKey || e.ctrlKey || e.altKey) return;
    if (k === ' ') {
      // Space only drives the demo while it is on screen and focus isn't on a control.
      if (typing(e) || !inView) return;
      e.preventDefault();
    }
    keyEls[k].classList.add('is-down');
  });
  document.addEventListener('keyup', (e) => {
    const k = e.key.toLowerCase();
    if (k in keyEls) keyEls[k].classList.remove('is-down');
  });
  window.addEventListener('blur', () => {
    Object.values(keyEls).forEach((el) => el.classList.remove('is-down'));
  });

  stage.querySelectorAll('[data-mouse]').forEach((el) => {
    const btn = Number(el.dataset.mouse);
    const down = (e) => {
      e.preventDefault();
      el.classList.add('is-down');
      clicks[btn].push(performance.now());
      renderCps();
    };
    const up = () => el.classList.remove('is-down');
    el.addEventListener('pointerdown', down);
    el.addEventListener('pointerup', up);
    el.addEventListener('pointerleave', up);
    el.addEventListener('contextmenu', (e) => e.preventDefault());
  });

  function renderCps() {
    const now = performance.now();
    for (const b of [0, 2]) {
      clicks[b] = clicks[b].filter((t) => now - t < 1000);
      cpsEls[b].textContent = `${clicks[b].length} CPS`;
    }
  }
  let inView = false;
  let cpsTimer = null;
  new IntersectionObserver(([entry]) => {
    inView = entry.isIntersecting;
    clearInterval(cpsTimer);
    if (inView) cpsTimer = setInterval(renderCps, 100);
  }).observe(stage);

  /* ---------- Lunar vs Dusk: bars grow and numbers count up together ---------- */
  const race = document.querySelector('[data-race]');
  if (race) {
    const nums = race.querySelectorAll('[data-count]');
    const finish = (el) => { el.textContent = el.dataset.count + ('plus' in el.dataset ? '+' : ''); };
    if (calm) {
      race.classList.add('in');
      nums.forEach(finish);
    } else {
      nums.forEach((el) => { el.textContent = '0'; });
      const easeOut = (t) => 1 - Math.pow(1 - t, 4);
      new IntersectionObserver(([entry], io) => {
        if (!entry.isIntersecting) return;
        io.disconnect();
        race.classList.add('in');
        nums.forEach((el) => {
          const end = Number(el.dataset.count);
          // Same length as the bar's CSS transition.
          const dur = 'plus' in el.dataset ? 1900 : 1400;
          const t0 = performance.now();
          const tick = (now) => {
            const t = Math.min(1, (now - t0) / dur);
            if (t === 1) return finish(el);
            el.textContent = Math.round(end * easeOut(t));
            requestAnimationFrame(tick);
          };
          requestAnimationFrame(tick);
        });
      }, { threshold: 0.5 }).observe(race);
    }
  }

  /* ---------- Copy install commands ---------- */
  document.querySelectorAll('[data-copy]').forEach((btn) => {
    const label = btn.querySelector('span');
    let timer;
    btn.addEventListener('click', async () => {
      const code = document.querySelector(btn.dataset.copy);
      let ok = false;
      try {
        await navigator.clipboard.writeText(code.textContent.trim());
        ok = true;
      } catch {
        // No clipboard permission: select the command so the old copy path,
        // or the visitor's own shortcut, can take it.
        const range = document.createRange();
        range.selectNodeContents(code);
        getSelection().removeAllRanges();
        getSelection().addRange(range);
        try { ok = document.execCommand('copy'); } catch { ok = false; }
      }
      label.textContent = ok ? 'Copied' : 'Selected';
      btn.classList.toggle('is-done', ok);
      clearTimeout(timer);
      timer = setTimeout(() => { label.textContent = 'Copy'; btn.classList.remove('is-done'); }, 1800);
    });
  });

  /* ---------- Detect OS ---------- */
  const ua = navigator.userAgent;
  const platform = (navigator.userAgentData && navigator.userAgentData.platform) || navigator.platform || '';
  let os = null;
  if (/Win/i.test(platform) || /Windows/i.test(ua)) os = 'win';
  else if (/Mac/i.test(platform) && !/iPhone|iPad/i.test(ua) && navigator.maxTouchPoints < 2) os = 'mac';
  else if (/Linux/i.test(platform) && !/Android/i.test(ua)) os = 'linux';
  if (os) {
    const group = document.querySelector(`.dl__group[data-os="${os}"]`);
    group.classList.add('is-mine');
    group.querySelector('.tag').hidden = false;
    group.parentElement.prepend(group);
    const names = { mac: 'macOS', win: 'Windows', linux: 'Linux' };
    const hero = document.querySelector('[data-hero-dl] span');
    if (hero) hero.textContent = `Download for ${names[os]}`;
  }

  /* ---------- Latest release from GitHub ---------- */
  const rows = document.querySelectorAll('.dl__row[data-asset]');
  const fmt = (bytes) => `${Math.round(bytes / 1e6)} MB`;
  const clearSkeletons = () => rows.forEach((row) => {
    const size = row.querySelector('.dl__size');
    if (size.querySelector('.skel')) size.textContent = '';
  });

  fetch(`https://api.github.com/repos/${REPO}/releases/latest`, { headers: { Accept: 'application/vnd.github+json' } })
    .then((r) => { if (!r.ok) throw new Error(r.status); return r.json(); })
    .then((release) => {
      const tag = document.getElementById('release-tag');
      if (release.tag_name) tag.textContent = `Latest release: ${release.tag_name}`;
      rows.forEach((row) => {
        const asset = (release.assets || []).find((a) => a.name.endsWith(row.dataset.asset));
        if (!asset) return;
        row.href = asset.browser_download_url;
        row.querySelector('.dl__size').textContent = fmt(asset.size);
      });
      clearSkeletons();
    })
    .catch(() => {
      clearSkeletons();
      document.querySelector('.dl__error').hidden = false;
    });
})();
