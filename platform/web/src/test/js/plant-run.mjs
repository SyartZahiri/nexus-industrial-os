// Runs the scripted hour of plant life in the TeaVM build and compares it with the JVM's text.
// Usage: node plant-run.mjs <nexus-core.js> <plant-jvm.txt>
import { readFileSync, copyFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

const [modulePath, expectedPath] = process.argv.slice(2);
const asModule = modulePath.replace(/\.js$/, '.mjs');
copyFileSync(modulePath, asModule);
const core = await import(pathToFileURL(asModule).href);

const started = Date.now();
const actual = core.plantScenario1h(4711).split('\n');
const expected = readFileSync(expectedPath, 'utf8').split('\n');
for (let i = 0; i < Math.max(expected.length, actual.length); i++) {
  if (expected[i] !== actual[i]) {
    console.error(`line ${i + 1} differs\n  JVM: ${expected[i]?.slice(0, 400)}\n  JS:  ${actual[i]?.slice(0, 400)}`);
    process.exit(1);
  }
}
console.log(`Plant hour in JavaScript matches the JVM on ${actual.length} lines (${Date.now() - started} ms).`);
