import '@fontsource/google-sans/400.css';
import '@fontsource/google-sans/500.css';
import '@fontsource/google-sans/700.css';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import App from './App';
import './theme.css';
import './drive.css';

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </StrictMode>,
);
