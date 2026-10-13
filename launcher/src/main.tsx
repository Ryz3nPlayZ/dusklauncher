import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import App from './App';
import { LEGACY, UI_STYLE } from './lib/ui';

/* one look's stylesheets, chosen at startup (Settings → Interface); the app
   renders once they're in, so the other look never flashes first */
const styles = LEGACY ? import('./design/legacy/index.css') : import('./design/index.css');

document.documentElement.dataset.ui = UI_STYLE;

void styles.finally(() =>
  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <App />
    </StrictMode>,
  ),
);
