import type { Answer, Command, NexusMessage, Query } from './types';

/** The one way the user interface talks to the plant (Spec 14), in the browser or against the server. */
export interface NexusClient {
  readonly mode: 'browser' | 'server';
  /** Starts the stream; returns a function that stops it. */
  connect(onMessage: (m: NexusMessage) => void): () => void;
  command(c: Command): Promise<Answer>;
  query<T>(q: Query): Promise<Answer<T>>;
  play(): Promise<void>;
  pause(): Promise<void>;
  setSpeed(speed: number): Promise<void>;
  reset(seed?: number): Promise<void>;
}

export async function createClient(mode = import.meta.env.VITE_NEXUS_MODE ?? 'browser'): Promise<NexusClient> {
  if (mode === 'server') return new (await import('./server/ServerClient')).ServerClient('');
  return new (await import('./browser/BrowserClient')).BrowserClient();
}
