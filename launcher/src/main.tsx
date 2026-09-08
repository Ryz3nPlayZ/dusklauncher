import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App';
import './design/tokens.css';
import './design/base.css';
import './design/components.css';
import './design/app.css';
import './design/home.css';
import './design/pages.css';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
