/// <reference lib="webworker" />
import * as core from '@nexus-core';
import { BrowserEngine, type WorkerRequest } from './engine';

const TICK_MS = 50;
const DEFAULT_SEED = 4711;
/** Created with the first message (hello), which carries the seed from the page URL. */
let engine: BrowserEngine | null = null;
let last = performance.now();

setInterval(() => {
  const now = performance.now();
  const messages = engine ? engine.tick(now - last) : [];
  last = now;
  for (const m of messages) postMessage({ message: m });
}, TICK_MS);

onmessage = (e: MessageEvent<WorkerRequest>) => {
  let answer: unknown;
  let messages: ReturnType<BrowserEngine['handle']>['messages'] = [];
  try {
    if (!engine) engine = new BrowserEngine(core, e.data.kind === 'hello' ? (e.data.seed ?? DEFAULT_SEED) : DEFAULT_SEED);
    ({ answer, messages } = engine.handle(e.data));
  } catch (err) {
    answer = { ok: false, status: 400, message: err instanceof Error ? err.message : 'Befehl fehlgeschlagen' };
  }
  postMessage({ id: e.data.id, answer });
  for (const m of messages) postMessage({ message: m });
};
