import DOMPurify from 'dompurify';
import { marked } from 'marked';
import { isTauri } from './api';

/** Modrinth bodies and changelogs are markdown with a little HTML; sanitize
 *  after render and strip anything that could script or navigate the launcher */
export function renderMarkdown(md: string): string {
  const html = marked.parse(md, { async: false, gfm: true, breaks: false }) as string;
  return DOMPurify.sanitize(html, {
    USE_PROFILES: { html: true },
    FORBID_TAGS: ['style', 'script', 'iframe', 'form', 'input', 'video', 'audio'],
    FORBID_ATTR: ['style', 'onerror', 'onload'],
  });
}

/** open a link in the system browser (the launcher never navigates) */
export async function openLink(url: string) {
  if (isTauri) {
    const { openUrl } = await import('@tauri-apps/plugin-opener');
    await openUrl(url);
  } else {
    window.open(url, '_blank', 'noopener');
  }
}

/** onClick for rendered markdown: its links open in the browser instead */
export function mdLinkClick(e: { target: EventTarget; preventDefault: () => void }) {
  const a = (e.target as HTMLElement).closest('a');
  if (a?.href) {
    e.preventDefault();
    void openLink(a.href);
  }
}
