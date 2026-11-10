import { createRoot } from 'react-dom/client';
import App from './App';
import './index.css';

// No <StrictMode>: its simulated unmount/remount tears down the separate React roots that
// drei's <Html> labels create, which React 19 reports as a race in development.
createRoot(document.getElementById('root')!).render(<App />);
