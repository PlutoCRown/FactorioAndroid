#!/usr/bin/env python3
"""Evaluate isolated legacy Mesa using a completed session in a debuggable launcher."""
import argparse
import json
import re
from pathlib import Path
import shlex
import subprocess
import tempfile
import time
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--window', action='store_true', help='Present known RGB bands through the Android Surface')
    parser.add_argument('--gl-version', help='Optional diagnostic GL version override')
    parser.add_argument('--output', type=Path, default=ROOT / '.cache/legacy-zink-probe.log')
    args = parser.parse_args()
    adb = ['adb', '-s', args.serial]
    package = 'dev.plutocrown.factorio'

    def call(*command):
        return subprocess.check_output(adb + list(command))

    report = json.loads(call('exec-out', 'run-as', package, 'cat', 'files/game-session-report.json'))
    progress = report['engineProbeProgress']
    if report.get('exitStatus') not in ('failed', 'normal', 'stopped', 'unknown', 'interrupted'):
        # A force-stop or APK upgrade may leave the last report without an exit record.
        # Refuse to stage providers if that report's engine is still alive.
        match = re.search(r'pid=(\d+)', progress.get('processDescription', ''))
        if not match:
            raise RuntimeError('No completed session or verifiable engine PID; end the game first')
        live = subprocess.run(adb + ['exec-out', 'run-as', package, 'cat',
                                    '/proc/' + match[1] + '/cmdline'], capture_output=True)
        if live.returncode == 0 and b'factorio' in live.stdout:
            raise RuntimeError('End the game session before running this diagnostic')
    directory = '/data/user/0/' + package + '/files/runtime/legacy-zink-evaluation'
    staged = '/data/local/tmp/factorio-legacy-zink-evaluation'
    archive = ROOT / '.cache/mesa-legacy-providers.zip'
    probe = ROOT / '.cache/legacy-zink-blend-probe'
    inputs = json.loads((ROOT / 'native-input/mesa-legacy/inputs.json').read_text())
    headers = ROOT / ('.cache/mesa-' + inputs['source']['version']) / 'include'
    subprocess.run([str(ROOT / '.cache/mesa-tools/bin/cross-cc'), '-O2', '-I',
                    str(headers),
                    str(ROOT / 'tests/device/ZinkBlendProbe.c'), '-ldl', '-o', str(probe)], check=True)
    with tempfile.TemporaryDirectory(prefix='legacy-zink-') as temporary:
        local = Path(temporary)
        with zipfile.ZipFile(archive) as capsule:
            for name in capsule.namelist():
                if name.startswith('/') or '..' in Path(name).parts:
                    raise ValueError('Unsafe provider archive entry')
            capsule.extractall(local)
        (local / 'mesa-vendor.json').write_text(json.dumps({
            'file_format_version': '1.0.0',
            'ICD': {'library_path': directory + '/libEGL_mesa.so.0'}}))
        (local / 'blend-probe').write_bytes(probe.read_bytes())
        call('shell', 'mkdir', '-p', staged)
        subprocess.run(adb + ['push', str(local) + '/.', staged], check=True)
    call('shell', 'run-as', package, 'mkdir', '-p', directory)
    call('shell', 'run-as', package, 'cp', '-R', staged + '/.', directory)
    call('shell', 'run-as', package, 'chmod', '700', directory + '/blend-probe')
    environment = {k: v for k, v in progress['extraEnvironment'].items()
                   if not k.startswith('REA_IME') and k not in
                   ('LD_PRELOAD', 'VK_INSTANCE_LAYERS', 'VK_LAYER_PATH', 'MESA_LOG_FILE',
                    'MESA_GL_VERSION_OVERRIDE')}
    environment.update(HOME='/data/user/0/' + package + '/files',
                       TMPDIR='/data/user/0/' + package + '/cache/x11', LC_ALL='C',
                       GLIBC_TUNABLES='glibc.pthread.rseq=0', LIBGL_DEBUG='verbose',
                       __EGL_VENDOR_LIBRARY_FILENAMES=directory + '/mesa-vendor.json',
                       LIBGL_DRIVERS_PATH=directory + '/dri', GBM_BACKENDS_PATH=directory + '/gbm')
    if args.gl_version:
        environment['MESA_GL_VERSION_OVERRIDE'] = args.gl_version
    command = progress['arguments'][:progress['arguments'].index('--config') - 1]
    index = command.index('--library-path') + 1
    command[index] = directory + ':' + command[index]
    command += [directory + '/blend-probe'] + (['--window'] if args.window else [])
    device = ['run-as', package, '/system/bin/env', '-i']
    device += [k + '=' + v for k, v in environment.items()]
    device += ['/system/bin/sh', '-c', 'exec "$@"', 'legacy-zink-probe'] + command
    # This existing debug-only entry starts the private X server without running a game.
    call('shell', 'am', 'start', '-n', package + '/com.termux.x11.MainActivity',
         '--ez', 'windowProbe', 'true', '--ez', 'windowGraphics', 'true',
         '--ez', 'windowPresentationProbe', str(args.window).lower())
    for _ in range(40):
        state = subprocess.run(adb + ['exec-out', 'run-as', package, 'cat',
                               'files/window-report.json'], capture_output=True)
        process = subprocess.run(adb + ['shell', 'pidof', package + ':window'], capture_output=True)
        try:
            window = json.loads(state.stdout) if state.returncode == 0 else {}
            alive = str(window.get('pid', -1)).encode() in process.stdout.split()
            if (alive and window.get('windowTransportPassed')
                    and window.get('phase') not in ('closing', 'finished', 'failed')):
                break
        except ValueError:
            pass
        time.sleep(0.2)
    else:
        raise RuntimeError('The private diagnostic X server did not become ready')
    result = subprocess.run(adb + ['shell', shlex.join(device)], timeout=60, capture_output=True)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(result.stdout + result.stderr)
    print((result.stdout + result.stderr).decode(errors='replace')[-12000:])
    print('Diagnostic exit:', result.returncode)
    print('Full log:', args.output)
    return result.returncode


if __name__ == '__main__':
    raise SystemExit(main())
