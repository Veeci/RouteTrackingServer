import '@mantine/core/styles.css';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App, createApp } from './app';

const root = document.getElementById('root');
if (root === null) throw new Error('index.html has no #root element');

const parts = createApp();
createRoot(root).render(
  <StrictMode>
    <App {...parts} />
  </StrictMode>,
);
