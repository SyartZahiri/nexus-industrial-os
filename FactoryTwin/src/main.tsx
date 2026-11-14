import { createRoot } from 'react-dom/client';
import App from './App';
import { createClient } from './nexus/client';
import { startNexus } from './store/factoryStore';
import './index.css';

// No <StrictMode>: its simulated unmount/remount tears down the separate React roots that
// drei's <Html> labels create, which React 19 reports as a race in development.
// The plant runs in the Java core: in a web worker (browser) or on the server, chosen by VITE_NEXUS_MODE.
startNexus(await createClient());
createRoot(document.getElementById('root')!).render(<App />);
