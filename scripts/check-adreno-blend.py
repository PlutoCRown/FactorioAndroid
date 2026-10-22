#!/usr/bin/env python3
"""Run the blend regression in a running debuggable launcher session."""
import argparse
import json
from pathlib import Path
import shlex
import subprocess

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True)
parser.add_argument('--mesa-directory', help='Optional device directory for an A/B provider test')
args = parser.parse_args()
adb = ['adb', '-s', args.serial]
package = 'dev.plutocrown.factorio'
report = json.loads(subprocess.check_output(adb + ['exec-out', 'run-as', package, 'cat', 'files/game-session-report.json']))
progress = report['engineProbeProgress']
inputs = json.loads((root / 'native-input/mesa-compat/inputs.json').read_text())
headers = root / ('.cache/mesa-' + inputs['source']['commit']) / 'include'
compiler = root / '.cache/mesa-tools/bin/cross-cc'
output = root / 'artifacts/ZinkBlendProbe'
subprocess.run([str(compiler), '-O2', '-I', str(headers), str(root / 'tests/device/ZinkBlendProbe.c'),
                '-ldl', '-o', str(output)], check=True)
staged = '/data/local/tmp/factorio-zink-blend-probe'
subprocess.run(adb + ['push', str(output), staged], check=True)
subprocess.run(adb + ['shell', 'run-as', package, 'cp', staged, 'cache/zink-blend-probe'], check=True)
environment = {k: v for k, v in progress['extraEnvironment'].items()
               if not k.startswith('REA_IME') and k not in ('LD_PRELOAD', 'VK_INSTANCE_LAYERS', 'VK_LAYER_PATH',
                   'GALLIUM_TRACE', 'GALLIUM_TRACE_TRIGGER', 'ZINK_DEBUG')}
environment.update(HOME='/data/user/0/' + package + '/files',
                   TMPDIR='/data/user/0/' + package + '/cache/x11', LC_ALL='C',
                   GLIBC_TUNABLES='glibc.pthread.rseq=0')
command = progress['arguments'][:progress['arguments'].index('--config') - 1]
if args.mesa_directory:
    index = command.index('--library-path') + 1
    command[index] = args.mesa_directory + ':' + command[index]
command += ['/data/user/0/' + package + '/cache/zink-blend-probe']
# The loader's installation path may contain '='. A shell exec keeps env from
# interpreting that executable pathname as another environment assignment.
device = ['run-as', package, '/system/bin/env', '-i'] + [k + '=' + v for k, v in environment.items()]
device += ['/system/bin/sh', '-c', 'exec "$@"', 'blend-probe'] + command
result = subprocess.run(adb + ['shell', shlex.join(device)], timeout=30)
raise SystemExit(result.returncode)
