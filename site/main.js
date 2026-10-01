(() => {
  const REPO = 'Ryz3nPlayZ/dusklauncher';

  /* ---------- Overworld / Nether toggle ---------- */
  const stack = document.querySelector('.shot-stack');
  document.querySelectorAll('[data-world-btn]').forEach((btn) => {
    btn.addEventListener('click', () => {
      const world = btn.dataset.worldBtn;
      stack.dataset.world = world;
      document.querySelectorAll('[data-world-btn]').forEach((b) =>
        b.setAttribute('aria-pressed', String(b === btn)));
    });
  });

  /* ---------- Keystrokes demo ---------- */
  const stage = document.getElementById('keystrokes');
  const keyEls = {};
  stage.querySelectorAll('[data-key]').forEach((el) => { keyEls[el.dataset.key] = el; });
  const clicks = { 0: [], 2: [] };
  if (matchMedia('(pointer: coarse)').matches) {
    stage.querySelector('.ks__hint').textContent = 'Try it: tap LMB and RMB as fast as you can.';
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

  /* ---------- Scroll reveals ---------- */
  const reveals = document.querySelectorAll('.reveal');
  if ('IntersectionObserver' in window) {
    const io = new IntersectionObserver((entries) => {
      entries.forEach((entry) => {
        if (!entry.isIntersecting) return;
        entry.target.classList.add('in');
        io.unobserve(entry.target);
      });
    }, { rootMargin: '0px 0px -8% 0px' });
    // Siblings that enter together stagger slightly.
    document.querySelectorAll('.bento, .facts').forEach((parent) => {
      [...parent.children].forEach((child, i) => child.style.setProperty('--rd', `${i * 80}ms`));
    });
    reveals.forEach((el) => io.observe(el));
  } else {
    reveals.forEach((el) => el.classList.add('in'));
  }

  /* ---------- Lunar vs Dusk count-up ---------- */
  const race = document.querySelector('[data-race]');
  const calm = matchMedia('(prefers-reduced-motion: reduce)').matches;
  if (race && !calm && 'IntersectionObserver' in window) {
    const nums = race.querySelectorAll('[data-count]');
    nums.forEach((el) => { el.textContent = '0'; });
    new MutationObserver((_, obs) => {
      if (!race.classList.contains('in')) return;
      obs.disconnect();
      nums.forEach((el) => {
        const end = Number(el.dataset.count);
        const plus = 'plus' in el.dataset;
        // Same length as the bar's CSS transition, ticking in whole steps like the bar.
        const dur = plus ? 1900 : 1400;
        const steps = plus ? 40 : 28;
        const t0 = performance.now();
        const tick = (now) => {
          const k = Math.min(1, Math.ceil(((now - t0) / dur) * steps) / steps);
          el.textContent = Math.round(end * k) + (k === 1 && plus ? '+' : '');
          if (k < 1) requestAnimationFrame(tick);
        };
        requestAnimationFrame(tick);
      });
    }).observe(race, { attributes: true, attributeFilter: ['class'] });
  }

  /* ---------- Copy Homebrew command ---------- */
  document.querySelectorAll('[data-copy]').forEach((btn) => {
    const label = btn.querySelector('span');
    btn.addEventListener('click', async () => {
      const text = document.querySelector(btn.dataset.copy).textContent.trim();
      try {
        await navigator.clipboard.writeText(text);
        label.textContent = 'Copied';
        btn.classList.add('is-done');
      } catch {
        label.textContent = 'Select it';
      }
      setTimeout(() => { label.textContent = 'Copy'; btn.classList.remove('is-done'); }, 1800);
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
