#!/usr/bin/env python3
"""Build the pinned compatibility GNU Mesa providers on macOS/Linux.

Only project-local caches/tools/outputs are written. Windows can reuse the
resulting .cache/mesa-runtime.zip through FACTORIO_MESA_ARCHIVE.
"""
import argparse
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import tarfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / '.cache'
INPUTS = json.loads((ROOT / 'native-input/mesa-compat/inputs.json').read_text())
TOOLS = CACHE / 'mesa-tools'
SYSROOT = CACHE / 'mesa-sysroot'
BUILD = CACHE / 'mesa-build'
SOURCE = CACHE / ('mesa-' + INPUTS['source']['commit'])
PROVIDERS = {
    'librea_compat_direct_present.so': 'direct-wsi/librea_compat_direct_present.so',
    'librea_compat_direct_icd.so': 'direct-wsi/librea_compat_direct_icd.so',
    'librea_compat_mesa_gallium.so': 'src/gallium/targets/dri/libgallium-26.1.0-devel.so',
    'librea_compat_mesa_egl.so': 'src/egl/libEGL_mesa.so.0.0.0',
    'librea_compat_mesa_glx.so': 'src/glx/libGLX_mesa.so.0.0.0',
    'librea_compat_mesa_gbm.so': 'src/gbm/libgbm.so.1.0.0',
    'librea_compat_mesa_gbm_dri.so': 'src/gbm/backends/dri/dri_gbm.so',
    'librea_compat_mesa_dril.so': 'src/gallium/targets/dril/libdril_dri.so',
}


def run(args, cwd=ROOT, env=None):
    subprocess.run([str(x) for x in args], cwd=cwd, env=env, check=True)


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda: f.read(131072), b''):
            h.update(chunk)
    return h.hexdigest()


def download(row):
    target = CACHE / row['file']
    if not target.is_file() or digest(target) != row['sha256']:
        print('Downloading verified build input:', row['file'], flush=True)
        temp = target.with_suffix(target.suffix + '.part')
        with urllib.request.urlopen(row['url'], timeout=120) as src, temp.open('wb') as dst:
            shutil.copyfileobj(src, dst)
        if digest(temp) != row['sha256']:
            temp.unlink()
            raise RuntimeError('Checksum mismatch: ' + row['file'])
        temp.replace(target)
    return target


def extract(archive, destination):
    # Inputs are hash-pinned, and destinations must still stay inside the cache.
    destination.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive) as t:
        for m in t.getmembers():
            if m.issym():
                # Provider aliases are created from the verified runtime below.
                continue
            p = (destination / m.name).resolve()
            if not p.is_relative_to(destination.resolve()):
                raise RuntimeError('Unsafe archive path: ' + m.name)
            if m.issym() and (Path(m.linkname).is_absolute() or '..' in Path(m.linkname).parts):
                # Debian dev-package symlinks are replaced by verified runtime DSOs below.
                continue
            if m.islnk() and ('..' in Path(m.linkname).parts or Path(m.linkname).is_absolute()):
                raise RuntimeError('Unsafe hard link')
            t.extract(m, destination)


def header_package(row):
    archive = download(row)
    temp = CACHE / 'mesa-dev-extract' / archive.name
    temp.mkdir(parents=True, exist_ok=True)
    run(['ar', '-x', archive], cwd=temp)
    extract(next(temp.glob('data.tar.*')), SYSROOT)


def write_executable(path, text):
    if not path.exists() or path.read_text() != text:
        path.write_text(text)
    path.chmod(0o755)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--providers-only', action='store_true',
                        help='Build the six GNU Mesa providers without an Android SDK/NDK; finish the two adapters on Windows.')
    options = parser.parse_args()
    if platform.system() not in ('Darwin', 'Linux'):
        raise RuntimeError('Build on macOS/Linux; copy the resulting ZIP for Windows builds.')
    CACHE.mkdir(exist_ok=True)
    if not (TOOLS / 'bin/meson').exists():
        run([sys.executable, '-m', 'venv', TOOLS])
        run([TOOLS / 'bin/pip', 'install', 'meson==1.11.2', 'ninja==1.13.2',
             'mako==1.3.12', 'pyyaml==6.0.3', 'packaging==26.3'])
    env = os.environ.copy()
    env['PATH'] = str(TOOLS / 'bin') + os.pathsep + env['PATH']
    env['PKG_CONFIG_SYSROOT_DIR'] = str(SYSROOT)
    for row in INPUTS['tools']:
        if not (TOOLS / 'bin' / row['name']).exists():
            extract(download(row), CACHE)
            folder = CACHE / (row['name'] + '-' + row['version'])
            run(['./configure', '--prefix=' + str(TOOLS)], cwd=folder, env=env)
            run(['make', '-j6'], cwd=folder, env=env)
            run(['make', 'install'], cwd=folder, env=env)
    source_archive = download(INPUTS['source'])
    if not SOURCE.is_dir():
        extract(source_archive, CACHE)
    patch = ROOT / 'native-input/mesa-compat/qualcomm-static-color-write.patch'
    # Accept only the pinned source with this exact patch, including cached builds.
    applied = subprocess.run(['patch', '-f', '-p1', '--dry-run', '-R', '-i', str(patch)],
                             cwd=SOURCE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if applied.returncode != 0:
        run(['patch', '-f', '-p1', '--forward', '-i', patch], cwd=SOURCE)
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(header_package, INPUTS['headers']))
    libs = SYSROOT / 'usr/lib/aarch64-linux-gnu'
    libs.mkdir(parents=True, exist_ok=True)
    # Dev packages supply headers, never the shipped runtime dependencies.
    for filename in ('runtime-glibc.json', 'window-clients.json', 'direct-dependencies.json', 'direct-zink.json'):
        manifest = json.loads((ROOT / 'app/src/main/assets' / filename).read_text())
        for row in manifest.get('libraries', []):
            for alias in row.get('aliases', []) or [row.get('name', '')]:
                if not alias or '/' in alias:
                    continue
                source = ROOT / 'app/src/main/jniLibs/arm64-v8a' / row['packagedName']
                for name in {alias, alias.split('.so.')[0] + '.so' if '.so.' in alias else alias}:
                    target = libs / name
                    target.unlink(missing_ok=True)
                    target.symlink_to(source)
    machine = ('aarch64' if platform.machine() in ('arm64', 'aarch64') else 'x86_64')
    host = machine + ('-macos' if platform.system() == 'Darwin' else '-linux')
    zig = ROOT / 'tools' / ('zig-' + host + '-0.15.2') / 'zig'
    # BuildImeNative verifies/downloads the compiler before this Gradle task.
    if not zig.is_file():
        raise RuntimeError('Run the Gradle buildImeNative task to prepare pinned Zig 0.15.2 first.')
    cc, cpp, ar = (TOOLS / 'bin' / n for n in ('cross-cc', 'cross-cxx', 'cross-ar'))
    write_executable(cc, '#!/bin/sh\nexec "' + str(zig) + '" cc -target aarch64-linux-gnu.2.38 "$@"\n')
    write_executable(cpp, '#!' + sys.executable + '\nimport os,sys\nargs=[];it=iter(sys.argv[1:])\n'
        'for arg in it:\n    if arg=="-Wl,--version-script":arg+="="+next(it)\n    args.append(arg)\n'
        'os.execv(' + repr(str(zig)) + ',["zig","c++","-target","aarch64-linux-gnu.2.38"]+args+["-Wl,--build-id=sha1"]+(["-s"] if "-shared" in args else []))\n')
    write_executable(ar, '#!/bin/bash\nset -e\nopts=${1//T/}\nshift\nexec "' + str(zig) + '" ar "$opts" "$@"\n')
    cross = CACHE / 'mesa-cross.ini'
    # Zig has its own libc headers and C++ runtime. It links against our GNU DSOs.
    includes = ['-I' + str(SYSROOT / 'usr/include'), '-I' + str(SYSROOT / 'usr/include/aarch64-linux-gnu')]
    links = ['-L' + str(libs), '-Wl,--allow-shlib-undefined', '-s']
    cross.write_text('[binaries]\nc = ' + repr(str(cc)) + '\ncpp = ' + repr(str(cpp)) + '\nar = ' + repr(str(ar)) +
        '\npkg-config = ' + repr(str(TOOLS / 'bin/pkgconf')) + '\n[host_machine]\nsystem = \'linux\'\n'
        'cpu_family = \'aarch64\'\ncpu = \'aarch64\'\nendian = \'little\'\n[properties]\nneeds_exe_wrapper = true\n'
        'sys_root = ' + repr(str(SYSROOT)) + '\npkg_config_libdir = ' + repr([str(libs / 'pkgconfig'), str(SYSROOT / 'usr/share/pkgconfig')]) +
        '\n[built-in options]\nc_args = ' + repr(includes) + '\ncpp_args = ' + repr(includes) + '\nc_link_args = ' + repr(links) + '\ncpp_link_args = ' + repr(links) + '\n')
    opts = ['--cross-file', cross, '--buildtype=release', '-Dplatforms=x11', '-Dgallium-drivers=zink', '-Dvulkan-drivers=',
            '-Dllvm=disabled', '-Dglx=dri', '-Degl=enabled', '-Dgbm=enabled', '-Dglvnd=enabled', '-Dgallium-va=disabled',
            '-Dvalgrind=disabled', '-Dlibunwind=disabled', '-Dlmsensors=disabled', '-Dzstd=disabled', '-Dbuild-tests=false',
            '-Dtools=', '-Dvideo-codecs=', '-Dgles1=disabled', '-Dgles2=enabled']
    run([TOOLS / 'bin/meson', 'setup', '--reconfigure' if (BUILD / 'build.ninja').exists() else '--clearcache', BUILD, SOURCE] + opts, env=env)
    # The wrapper normalizes Zig linker flags; force relinking when rebuilding a capsule.
    for relative in PROVIDERS.values():
        if not relative.startswith('direct-wsi/'):
            (BUILD / relative).unlink(missing_ok=True)
    run([TOOLS / 'bin/ninja', '-C', BUILD, '-j6'], env=env)
    if options.providers_only:
        metadata = 'source=' + INPUTS['source']['commit'] + '\nversion=' + INPUTS['source']['version'] + '\n'
        inputs_hash = hashlib.sha256()
        for name in ('native-input/mesa-compat/inputs.json', 'native-input/mesa-compat/qualcomm-static-color-write.patch', 'scripts/build-mesa-runtime.py'):
            inputs_hash.update(name.encode() + b'\0' + (ROOT / name).read_bytes().replace(b'\r\n', b'\n'))
        metadata += 'mesaBuildInputsSha256=' + inputs_hash.hexdigest() + '\n'
        archive = CACHE / 'mesa-providers.zip'
        with zipfile.ZipFile(archive.with_suffix('.pending'), 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
            for name, relative in PROVIDERS.items():
                if relative.startswith('direct-wsi/'):
                    continue
                target = BUILD / relative
                metadata += name + '.bytes=' + str(target.stat().st_size) + '\n' + name + '.sha256=' + digest(target) + '\n'
                z.write(target, name)
            z.writestr('mesa-native.properties', metadata)
        archive.with_suffix('.pending').replace(archive)
        print('Built GNU Mesa providers (no Android SDK/NDK):', archive, flush=True)
        return
    # Project-owned WSI: compile the actual source used by the compatibility path.
    wsi = ROOT / 'native-input/direct-wsi'
    native = BUILD / 'direct-wsi'
    native.mkdir(exist_ok=True)
    objects = []
    for name in ('icd', 'instance', 'device', 'swapchain', 'wsi', 'native-window-surface'):
        source = wsi / (name + '.c') if name == 'native-window-surface' else wsi / 'upstream' / (name + '.c')
        obj = native / (name + '.o')
        run([zig, 'cc', '-target', 'aarch64-linux-gnu.2.38', '-std=c11', '-O2', '-fPIC', '-fvisibility=hidden',
             '-I', wsi / 'include', '-I', wsi / 'upstream', '-c', source, '-o', obj])
        objects.append(obj)
    runtime = ROOT / 'app/src/main/jniLibs/arm64-v8a'
    dependencies = [runtime / name for name in ('librea_hybris_common.so', 'librea_direct_hardware.so',
                    'librea_direct_gralloc.so', 'librea_direct_ui.so', 'librea_xcb.so')]
    for row in json.loads((ROOT / 'app/src/main/assets/direct-dependencies.json').read_text())['libraries']:
        if 'libX11-xcb.so.1' in row.get('aliases', []):
            dependencies.append(runtime / row['packagedName'])
    run([zig, 'cc', '-target', 'aarch64-linux-gnu.2.38', '-shared', '-s', '-Wl,--build-id=sha1',
         '-Wl,--no-undefined', '-Wl,--allow-shlib-undefined', '-Wl,-soname,librea_direct_icd.so'] +
         objects + dependencies + ['-pthread', '-ldl', '-o', native / 'librea_compat_direct_icd.so'])
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or str(Path.home() / 'Library/Android/sdk'))
    ndk = sdk / 'ndk/27.0.12077973/toolchains/llvm/prebuilt' / ('darwin-x86_64' if platform.system() == 'Darwin' else 'linux-x86_64')
    clang = ndk / 'bin/aarch64-linux-android26-clang'
    if not clang.is_file():
        raise RuntimeError('Source building the Surface receiver requires Android NDK 27.0.12077973 in ANDROID_HOME.')
    run([clang, '-I', SOURCE / 'include', '-std=c11', '-O2', '-shared', '-fPIC', '-fvisibility=hidden', '-s', '-Wl,--build-id=sha1',
         '-Wl,-z,max-page-size=16384', ROOT / 'native-input/direct-present-server.c', '-ldl',
         '-o', native / 'librea_compat_direct_present.so'])
    source_hash = hashlib.sha256()
    for file in sorted((p for p in wsi.rglob('*') if p.is_file() and p.suffix in ('.c', '.h', '.inc')), key=lambda p: p.relative_to(wsi).as_posix()):
        source_hash.update(file.relative_to(wsi).as_posix().encode() + b'\0' + file.read_bytes().replace(b'\r\n', b'\n'))
    source_hash.update(b'direct-present-server.c\0' + (ROOT / 'native-input/direct-present-server.c').read_bytes().replace(b'\r\n', b'\n'))
    for name in ('native-input/mesa-compat/inputs.json',
                 'native-input/mesa-compat/qualcomm-static-color-write.patch',
                 'scripts/build-mesa-runtime.py'):
        source_hash.update(name.encode() + b'\0' + (ROOT / name).read_bytes().replace(b'\r\n', b'\n'))
    staging = CACHE / 'mesa-package'
    staging.mkdir(exist_ok=True)
    metadata = 'source=' + INPUTS['source']['commit'] + '\nversion=' + INPUTS['source']['version'] + '\nicdSourceSha256=' + source_hash.hexdigest() + '\n'
    archive = CACHE / 'mesa-runtime.zip'
    temporary = archive.with_suffix('.pending')
    with zipfile.ZipFile(temporary, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for name, relative in PROVIDERS.items():
            target = staging / name
            shutil.copy2(BUILD / relative, target)
            metadata += name + '.bytes=' + str(target.stat().st_size) + '\n' + name + '.sha256=' + digest(target) + '\n'
            z.write(target, name)
        z.writestr('mesa-native.properties', metadata)
    temporary.replace(archive)
    print('Built compatible Mesa runtime:', archive, 'sha256=' + digest(archive), flush=True)


if __name__ == '__main__':
    main()
