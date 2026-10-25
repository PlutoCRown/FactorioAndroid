#!/usr/bin/env python3
"""Build isolated legacy Zink providers for compatibility evaluation, without an Android SDK."""
import argparse
import ast
import importlib.util
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / '.cache'
INPUTS = json.loads((ROOT / 'native-input/mesa-legacy/inputs.json').read_text())
VERSION = INPUTS['source']['version']
SOURCE = CACHE / ('mesa-' + VERSION)
BUILD = CACHE / 'mesa-legacy-build'
TOOLS = CACHE / 'mesa-tools/bin'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jobs', type=int, default=6)
    args = parser.parse_args()
    if args.jobs < 1:
        parser.error('--jobs must be positive')
    spec = importlib.util.spec_from_file_location('mesa_build', ROOT / 'scripts/build-mesa-runtime.py')
    common = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(common)
    cross = CACHE / 'mesa-cross.ini'
    if not cross.is_file() or str(ROOT) not in cross.read_text() or not (TOOLS / 'meson').is_file():
        common.run([sys.executable, ROOT / 'scripts/build-mesa-runtime.py', '--providers-only'])
    # This release's export map includes optional drivers that are not built.
    # GNU ld accepts those absent symbols; make the same behavior explicit for lld.
    legacy_cross = CACHE / 'mesa-legacy-cross.ini'
    cross_text = re.sub(r'^(c|cpp)_link_args = (.+)$',
                        lambda m: m[1] + '_link_args = '
                        + repr(ast.literal_eval(m[2]) + ['-Wl,--undefined-version']),
                        cross.read_text(), flags=re.MULTILINE)
    if not legacy_cross.is_file() or legacy_cross.read_text() != cross_text:
        legacy_cross.write_text(cross_text)
    archive = common.download(INPUTS['source'])
    if not SOURCE.is_dir():
        common.extract(archive, CACHE)
    for name in ('build-compat.patch', 'presentation.patch', 'rgba-window.patch', 'texel-buffer-limit.patch'):
        patch = ROOT / 'native-input/mesa-legacy' / name
        applied = subprocess.run(['patch', '-f', '-p1', '--dry-run', '-R', '-i', str(patch)],
                                 cwd=SOURCE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        if applied.returncode != 0:
            common.run(['patch', '-f', '-p1', '--forward', '-i', patch], cwd=SOURCE)
    for header in INPUTS.get('headers', []):
        common.header_package(header)
    environment = os.environ.copy()
    environment['PATH'] = str(TOOLS) + os.pathsep + environment['PATH']
    environment['PKG_CONFIG_SYSROOT_DIR'] = str(CACHE / 'mesa-sysroot')
    options = ['--cross-file', legacy_cross, '--buildtype=release', '-Dplatforms=x11',
               '-Dgallium-drivers=zink', '-Dvulkan-drivers=', '-Dllvm=disabled', '-Dglx=dri',
               '-Degl=enabled', '-Dgbm=enabled', '-Dglvnd=true', '-Dgallium-va=disabled',
               '-Dgallium-vdpau=disabled', '-Dvalgrind=disabled', '-Dlibunwind=disabled',
               '-Dlmsensors=disabled', '-Dzstd=disabled', '-Dbuild-tests=false', '-Dtools=',
               '-Dvideo-codecs=', '-Dgles1=disabled', '-Dgles2=enabled']
    for language in ('c', 'cpp'):
        flags = re.search(r'^' + language + r'_link_args = (.+)$', cross_text, re.MULTILINE)[1]
        options.append('-D' + language + '_link_args=' + flags)
    common.run([TOOLS / 'meson', 'setup', '--reconfigure' if (BUILD / 'build.ninja').exists()
                else '--clearcache', BUILD, SOURCE] + options, env=environment)
    common.run([TOOLS / 'ninja', '-C', BUILD, '-j' + str(args.jobs)], env=environment)
    providers = {
        'dri/zink_dri.so': 'src/gallium/targets/dri/libgallium_dri.so',
        'libEGL_mesa.so.0': 'src/egl/libEGL_mesa.so.0.0.0',
        'libGLX_mesa.so.0': 'src/glx/libGLX_mesa.so.0.0.0',
        'libgbm.so.1': 'src/gbm/libgbm.so.1.0.0',
        'libglapi.so.0': 'src/mapi/shared-glapi/libglapi.so.0.0.0',
    }
    inputs_digest = hashlib.sha256()
    for path in sorted((ROOT / 'native-input/mesa-legacy').glob('*')) + [Path(__file__).resolve()]:
        if path.suffix not in ('.patch', '.json', '.py'):
            continue
        inputs_digest.update(path.relative_to(ROOT).as_posix().encode() + b'\0')
        inputs_digest.update(path.read_text().replace('\r\n', '\n').encode())
    metadata = {'version': VERSION, 'source': INPUTS['source'], 'providers': {},
                'buildInputsSha256': inputs_digest.hexdigest()}

    properties = {'version': VERSION, 'buildInputsSha256': inputs_digest.hexdigest()}
    output = CACHE / 'mesa-legacy-providers.zip'
    temporary = output.with_suffix('.pending')
    with zipfile.ZipFile(temporary, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as capsule:
        for name, relative in providers.items():
            path = BUILD / relative
            metadata['providers'][name] = {'bytes': path.stat().st_size, 'sha256': common.digest(path)}
            capsule.write(path, name)
            properties[name + '.bytes'] = str(path.stat().st_size)
            properties[name + '.sha256'] = common.digest(path)
        capsule.writestr('source-build.json', json.dumps(metadata, indent=2) + '\n')
        capsule.writestr('legacy-mesa.properties', ''.join(k + '=' + v + '\n' for k, v in sorted(properties.items())))
    temporary.replace(output)
    print('Built isolated evaluation providers:', output, flush=True)


if __name__ == '__main__':
    main()
