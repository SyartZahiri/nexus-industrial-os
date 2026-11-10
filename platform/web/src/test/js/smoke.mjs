// Runs the TeaVM build of the core and compares its self-test with the output the JVM produced.
// Usage: node smoke.mjs <nexus-core.js> <selftest-jvm.txt>
import { readFileSync, copyFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

const [modulePath, expectedPath] = process.argv.slice(2);
// Node only treats .mjs (or a package with "type": "module") as an ES module.
const asModule = modulePath.replace(/\.js$/, '.mjs');
copyFileSync(modulePath, asModule);

const core = await import(pathToFileURL(asModule).href);
const expected = readFileSync(expectedPath, 'utf8').trim().split('\n');
const actual = core.selfTest(4711).trim().split('\n');

let failed = false;
for (let i = 0; i < Math.max(expected.length, actual.length); i++) {
  if (expected[i] !== actual[i]) {
    failed = true;
    console.error(`line ${i + 1} differs\n  JVM: ${expected[i]}\n  JS:  ${actual[i]}`);
  }
}
if (failed) process.exit(1);
console.log(`JavaScript build matches the JVM on ${actual.length} lines.`);
