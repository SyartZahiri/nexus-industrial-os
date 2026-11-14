// Builds the Java core for the browser (TeaVM) into platform/web/target/javascript/nexus-core.js.
// npm runs scripts through cmd on Windows, where ./mvnw does not work, hence this small launcher.
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../..', import.meta.url));
const windows = process.platform === 'win32';
const result = spawnSync(windows ? 'mvnw.cmd' : './mvnw',
  ['-B', '-q', '-pl', 'platform/web', '-am', 'package', '-DskipTests'],
  { cwd: root, stdio: 'inherit', shell: windows });
process.exit(result.status ?? 1);
