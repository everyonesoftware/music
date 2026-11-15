"""Build a Windows-only, unsigned-preview Velopack installer from committed sources.

This orchestrator never publishes a release. Use windows-release.py separately.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import struct
import subprocess
import sys

RELEASE_INPUTS = [
    'apps/windows', 'scripts/build-windows-installer.py', 'scripts/windows-release.py',
    'tests/test_windows_installer.py', 'tests/test_windows_release.py',
    '.github/workflows/windows-build.yml', '.github/workflows/windows-release.yml',
]


def release_tool(root):
    spec = importlib.util.spec_from_file_location('music_windows_release', Path(root) / 'scripts/windows-release.py')
    if spec is None or spec.loader is None:
        raise RuntimeError('Windows release tooling could not be loaded.')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def require_clean_sources(root):
    result = subprocess.run(['git', '-C', str(root), 'status', '--porcelain', '--untracked-files=all', '--', *RELEASE_INPUTS], capture_output=True, text=True, check=True)
    if result.stdout.strip():
        raise ValueError('Installer release inputs must be committed in a clean checkout. Do not label uncommitted code as a Git release.')


def build_plan(root, output, info):
    root, output = Path(root).resolve(), Path(output).resolve()
    windows = root / 'apps/windows'
    publish = output / 'publish'
    releases = output / 'releases'
    return [
        ['dotnet', 'tool', 'restore', '--tool-manifest', str(windows / '.config/dotnet-tools.json')],
        ['dotnet', 'test', str(windows / 'Music.Core.Tests/Music.Core.Tests.csproj'), '-c', 'Release'],
        ['dotnet', 'run', '--project', str(windows / 'validation/LogicChecks.csproj'), '-c', 'Release'],
        [sys.executable, '-m', 'unittest', 'discover', '-s', str(windows / 'validation'), '-p', 'test_source_contracts.py', '-v'],
        [sys.executable, '-m', 'unittest', 'discover', '-s', str(root / 'tests'), '-p', 'test_windows_*.py', '-v'],
        ['dotnet', 'publish', str(windows / 'Music.App/Music.App.csproj'), '-c', 'Release', '-r', 'win-x64', '--self-contained', 'true',
         '-p:WindowsAppSDKSelfContained=true', '-p:ContinuousIntegrationBuild=true', '-p:Version=' + info['version'],
         '-p:InformationalVersion=' + info['version'] + '+' + info['sha'], '-o', str(publish)],
        ['dotnet', 'tool', 'run', 'vpk', '--', '[win]', 'pack', '--packId', 'MusicWindows', '--packVersion', info['version'],
         '--packDir', str(publish), '--mainExe', 'Music.exe', '--packTitle', 'Music', '--packAuthors', 'Everyone Software',
         '--runtime', 'win-x64', '--channel', 'win-x64', '--framework', 'vcredist143-x64', '--noPortable', '--delta', 'None',
         '--shortcuts', 'StartMenuRoot', '--outputDir', str(releases), '--yes', '--skip-updates'],
    ]


def validate_payload(directory, version=None):
    directory = Path(directory)
    required = ['Music.exe', 'Music.dll', 'Music.deps.json', 'Music.runtimeconfig.json',
                'coreclr.dll', 'hostfxr.dll', 'Microsoft.UI.Xaml.dll', 'Microsoft.WindowsAppRuntime.dll']
    for name in required:
        path = directory / name
        if path.is_symlink() or not path.is_file() or path.stat().st_size == 0:
            raise ValueError('Incomplete self-contained Windows payload: missing ' + name)
    for path in directory.rglob('*'):
        if path.is_symlink():
            raise ValueError('Windows payload must not contain symlinks.')
    with (directory / 'Music.exe').open('rb') as stream:
        header = stream.read(64)
        if len(header) != 64 or header[:2] != b'MZ':
            raise ValueError('Music.exe is not a Windows PE executable.')
        stream.seek(struct.unpack_from('<I', header, 60)[0])
        pe = stream.read(6)
        if len(pe) != 6 or pe[:4] != b'PE\0\0' or struct.unpack_from('<H', pe, 4)[0] != 0x8664:
            raise ValueError('Music.exe must target Windows x64.')
    runtime = json.loads((directory / 'Music.runtimeconfig.json').read_text(encoding='utf-8'))['runtimeOptions']
    if 'framework' in runtime or 'frameworks' in runtime or not runtime.get('includedFrameworks'):
        raise ValueError('The .NET runtime must be self-contained.')
    dependencies = json.loads((directory / 'Music.deps.json').read_text(encoding='utf-8'))
    if 'win-x64' not in dependencies['runtimeTarget']['name']:
        raise ValueError('Payload dependency target must be win-x64.')
    if version is not None and 'Music/' + version not in dependencies['libraries']:
        raise ValueError('Published app version does not match release metadata.')


def build(root, output, *, allow_unsigned=False, host=None, run=None):
    if (host or os.name) != 'nt':
        raise RuntimeError('A real self-contained installer release build requires Windows. Linux compile/pack probes are not release builds.')
    if not allow_unsigned:
        raise ValueError('This tooling produces unsigned previews. Pass --allow-unsigned explicitly; signed stable releases are not enabled.')
    root, output = Path(root).resolve(), Path(output).resolve()
    require_clean_sources(root)
    tool = release_tool(root)
    info = tool.version(root)
    if output.exists() and (output.is_symlink() or not output.is_dir() or any(output.iterdir())):
        raise ValueError('Use a new or empty output directory; refusing to mix releases or overwrite a package.')
    output.mkdir(parents=True, exist_ok=True)
    plan = build_plan(root, output, info)
    runner = run or subprocess.run
    for command in plan:
        if 'pack' in command:
            validate_payload(output / 'publish', info['version'])
        runner(command, cwd=root / 'apps/windows', check=True)
    require_clean_sources(root)
    if tool.version(root) != info:
        raise ValueError('Source HEAD changed during the installer build.')
    metadata = tool.manifest(output / 'releases', info)
    return metadata


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-directory', type=Path)
    parser.add_argument('--allow-unsigned', action='store_true')
    parser.add_argument('--plan', action='store_true', help='Show real command arguments without building, packaging, or publishing.')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    info = release_tool(root).version(root)
    output = args.output_directory or root / 'dist/windows' / info['version']
    if args.plan:
        result = {'release': info, 'cwd': str(root / 'apps/windows'), 'commands': build_plan(root, output, info)}
    else:
        result = build(root, output, allow_unsigned=args.allow_unsigned)
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
