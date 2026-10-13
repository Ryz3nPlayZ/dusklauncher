/* Which look the launcher wears: the modern one, or the pixel style it had
   before. The stylesheets and a few components pick their version once, at
   startup, so switching saves the choice and reloads the window. Kept in this
   webview's storage, since it has to be known before anything else loads. */

export type UiStyle = 'modern' | 'legacy';

const KEY = 'dusk.ui';

function read(): UiStyle {
  try {
    return localStorage.getItem(KEY) === 'legacy' ? 'legacy' : 'modern';
  } catch {
    return 'modern';
  }
}

/** the look this window started with */
export const UI_STYLE: UiStyle = read();
export const LEGACY = UI_STYLE === 'legacy';

/** Saves the look for the next start. */
export function saveUiStyle(style: UiStyle) {
  try {
    localStorage.setItem(KEY, style);
  } catch {
    /* storage blocked: the choice just won't stick */
  }
}

/** Reloads the window so the saved look takes over. The game and any
    download keep running in the backend. */
export function restartUi() {
  window.location.reload();
}
