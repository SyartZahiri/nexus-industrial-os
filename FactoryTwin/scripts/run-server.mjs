// Starts the NEXUS server (Spring Boot, embedded PostgreSQL, OPC UA) for the frontend's server mode:
// builds the application jar with its modules, then runs it from the repository root.
import { spawn, spawnSync } from 'node:child_process';
import { readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../..', import.meta.url));
const windows = process.platform === 'win32';
// absolute path: the launcher may start us with a working directory cmd does not search
const mvnw = windows ? `"${root}mvnw.cmd"` : './mvnw';
const build = spawnSync(mvnw, ['-B', '-q', '-pl', 'platform/app', '-am', 'package', '-DskipTests'], {
  cwd: root,
  stdio: 'inherit',
  shell: windows,
});
if (build.status !== 0) process.exit(build.status ?? 1);

const target = `${root}platform/app/target`;
const jar = readdirSync(target).find((f) => /^nexus-app-.*\.jar$/.test(f) && !f.endsWith('-plain.jar'));
if (!jar) {
  console.error('Kein nexus-app-*.jar in platform/app/target');
  process.exit(1);
}
const child = spawn('java', ['-jar', `${target}/${jar}`], { cwd: root, stdio: 'inherit' });
child.on('exit', (code) => process.exit(code ?? 1));
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => child.kill(signal));
