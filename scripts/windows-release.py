"""Windows Velopack integrity and guarded unsigned-preview publication (stdlib)."""
import argparse
import hashlib
import json
import os
from pathlib import Path
from pathlib import PurePosixPath
import re
import stat
import struct
import subprocess
import tempfile
import zipfile
import xml.etree.ElementTree as ET


def digest(path, algorithm='sha256'):
    h = hashlib.new(algorithm)
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def require_x64_pe(stream):
    header = stream.read(64)
    if len(header) != 64 or header[:2] != b'MZ':
        raise ValueError('Expected a Windows x64 PE executable.')
    stream.seek(struct.unpack_from('<I', header, 60)[0])
    pe = stream.read(6)
    if len(pe) != 6 or pe[:4] != b'PE\0\0' or struct.unpack_from('<H', pe, 4)[0] != 0x8664:
        raise ValueError('Expected a Windows x64 PE executable.')


def validate_package_payload(archive, expected, values):
    if any(values.get(key) != value for key, value in {'channel':'win-x64', 'mainExe':'Music.exe', 'os':'win', 'rid':'win-x64'}.items()):
        raise ValueError('Windows package runtime/channel metadata mismatch.')
    entries = archive.infolist()
    names = [entry.filename for entry in entries]
    if len({name.casefold() for name in names}) != len(names):
        raise ValueError('Duplicate paths in Windows payload.')
    for entry in entries:
        path = PurePosixPath(entry.filename)
        if path.is_absolute() or '..' in path.parts or '\\' in entry.filename or ':' in entry.filename or stat.S_ISLNK(entry.external_attr >> 16):
            raise ValueError('Unsafe path in Windows payload.')
    # Windows file lookup is case-insensitive; native publish emits Microsoft.ui.xaml.dll.
    # The duplicate-path check above rejects ambiguous case aliases before lookup.
    paths = {name.casefold(): name for name in names}
    required = ['Music.exe', 'Music.dll', 'Music.deps.json', 'Music.runtimeconfig.json',
                'coreclr.dll', 'hostfxr.dll', 'Microsoft.UI.Xaml.dll', 'Microsoft.WindowsAppRuntime.dll']
    for name in required:
        actual = paths.get(('lib/app/' + name).casefold())
        if actual is None or archive.getinfo(actual).file_size == 0:
            raise ValueError('Incomplete self-contained Windows payload: missing ' + name)
    with archive.open(paths['lib/app/music.exe']) as stream:
        require_x64_pe(stream)
    for filename in ['Music.runtimeconfig.json', 'Music.deps.json']:
        if archive.getinfo(paths[('lib/app/' + filename).casefold()]).file_size > 8 * 1024 * 1024:
            raise ValueError('Unexpectedly large runtime metadata.')
    runtime = json.loads(archive.read(paths['lib/app/music.runtimeconfig.json']))['runtimeOptions']
    if 'framework' in runtime or 'frameworks' in runtime or not runtime.get('includedFrameworks'):
        raise ValueError('The .NET runtime must be self-contained.')
    dependencies = json.loads(archive.read(paths['lib/app/music.deps.json']))
    if 'win-x64' not in dependencies['runtimeTarget']['name'] or 'Music/' + expected['version'] not in dependencies['libraries']:
        raise ValueError('Windows payload runtime/version mismatch.')


def inventory(directory, expected):
    count = expected.get('count')
    if type(count) is not int or not 1 <= count <= 65534 or expected.get('version') != f'0.1.{count}' or expected.get('tag') != f'windows-v0.1.{count}' or not re.fullmatch(r'[0-9a-f]{40}|[0-9a-f]{64}', expected.get('sha', '')):
        raise ValueError('Invalid Windows version/commit metadata.')
    directory = Path(directory)
    names = [f"MusicWindows-{expected['version']}-win-x64-full.nupkg", 'MusicWindows-win-x64-Setup.exe', 'releases.win-x64.json', 'assets.win-x64.json', 'RELEASES-win-x64']
    allowed = set(names) | {'windows-release.json', 'SHA256SUMS'}
    if directory.is_symlink() or not directory.is_dir():
        raise ValueError('Asset directory must be a real directory.')
    for path in directory.iterdir():
        if path.name not in allowed or path.is_symlink() or not path.is_file():
            raise ValueError('Unexpected, nested, or symlink asset.')
    if not set(names) <= {path.name for path in directory.iterdir()}:
        raise ValueError('Missing required Windows asset.')
    package = directory / names[0]
    with (directory / names[1]).open('rb') as stream:
        require_x64_pe(stream)
    index = json.loads((directory / 'assets.win-x64.json').read_text(encoding='utf-8'))
    wanted_index = [{'RelativeFileName': names[1], 'Type': 'Installer'}, {'RelativeFileName': names[0], 'Type': 'Full'}]
    if not isinstance(index, list) or len(index) != 2 or any(entry not in wanted_index for entry in index) or index[0] == index[1]:
        raise ValueError('Velopack asset index mismatch.')
    legacy = (directory / 'RELEASES-win-x64').read_text(encoding='utf-8-sig').strip().split()
    if len(legacy) != 3 or legacy[0].lower() != digest(package, 'sha1') or legacy[1] != names[0] or legacy[2] != str(package.stat().st_size):
        raise ValueError('Velopack legacy feed mismatch.')
    feed = json.loads((directory / names[2]).read_text(encoding='utf-8'))
    assets = feed['Assets']
    if len(assets) != 1:
        raise ValueError('Expected exactly one full package in feed.')
    asset = assets[0]
    if (asset.get('PackageId'), asset.get('Version'), asset.get('Type'), asset.get('FileName')) != ('MusicWindows', expected['version'], 'Full', names[0]):
        raise ValueError('Feed package identity/version mismatch.')
    if asset.get('Size') != package.stat().st_size:
        raise ValueError('Feed package size mismatch.')
    if not asset.get('SHA256') and not asset.get('SHA1'):
        raise ValueError('Feed package hash missing.')
    for key, algorithm in [('SHA256', 'sha256'), ('SHA1', 'sha1')]:
        if key in asset and asset[key].lower() != digest(package, algorithm):
            raise ValueError('Feed package hash mismatch.')
    with zipfile.ZipFile(package) as archive:
        specs = [name for name in archive.namelist() if name.endswith('.nuspec')]
        if len(specs) != 1:
            raise ValueError('Expected exactly one nuspec.')
        tree = ET.fromstring(archive.read(specs[0]))
        values = {element.tag.rsplit('}', 1)[-1]: element.text for element in tree.iter()}
        if values.get('id') != 'MusicWindows' or values.get('version') != expected['version']:
            raise ValueError('NuGet package identity/version mismatch.')
        validate_package_payload(archive, expected, values)
    return [{'name': name, 'bytes': (directory / name).stat().st_size, 'sha256': digest(directory / name)} for name in sorted(names)]


def checksums(artifacts):
    return ''.join(f"{item['sha256']}  {item['name']}\n" for item in artifacts)


def manifest(directory, expected, signed=False):
    if signed:
        raise ValueError('Verified signing is unsupported; only unsigned previews are allowed.')
    directory = Path(directory)
    artifacts = inventory(directory, expected)
    data = dict(expected, platform='windows-x64', package_id='MusicWindows', runtime='win-x64', channel='win-x64', signing='unsigned-preview', artifacts=artifacts)
    (directory / 'windows-release.json').write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')
    (directory / 'SHA256SUMS').write_text(checksums(artifacts), encoding='utf-8')
    return validate(directory, expected)


def validate(directory, expected):
    directory = Path(directory)
    data = json.loads((directory / 'windows-release.json').read_text(encoding='utf-8'))
    wanted = dict(expected, platform='windows-x64', package_id='MusicWindows', runtime='win-x64', channel='win-x64', signing='unsigned-preview', artifacts=inventory(directory, expected))
    if data != wanted:
        raise ValueError('Release metadata/integrity mismatch.')
    if (directory / 'SHA256SUMS').read_text(encoding='utf-8') != checksums(wanted['artifacts']):
        raise ValueError('Checksum inventory mismatch.')
    return data


def publish(root, directory, repository, run=None):
    if not isinstance(repository, str) or not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository):
        raise ValueError('Invalid GitHub repository.')
    expected = version(root)
    status = subprocess.run(['git', '-C', str(root), 'status', '--porcelain', '--untracked-files=all'], capture_output=True, text=True)
    if status.returncode or status.stdout.strip():
        raise ValueError('Publication requires a clean Git checkout; dirty source is unsupported.')
    local = validate(directory, expected)
    run = run or subprocess.run
    tag = expected['tag']
    names = [a['name'] for a in local['artifacts']] + ['windows-release.json', 'SHA256SUMS']

    def gh(*args, optional=False):
        result = run(['gh', *args], capture_output=True, text=True)
        if result.returncode:
            missing = re.search(r'\bhttp 404\b', result.stderr.lower()) or result.stderr.strip().lower() == 'release not found'
            if optional and missing:
                return None
            raise RuntimeError('GitHub command failed: ' + result.stderr.strip())
        return result.stdout

    def view():
        data = gh('release', 'view', tag, '--repo', repository, '--json', 'isDraft,isPrerelease,targetCommitish,assets', optional=True)
        return json.loads(data) if data is not None else None

    def tag_commit(optional=False):
        data = gh('api', f'repos/{repository}/git/ref/tags/{tag}', optional=optional)
        if data is None:
            return None
        obj = json.loads(data)['object']
        if obj['type'] == 'commit':
            return obj['sha']
        if obj['type'] != 'tag':
            raise ValueError('Unexpected Git tag object.')
        response = gh('api', f'repos/{repository}/commits/{tag}')
        assert response is not None
        return json.loads(response)['sha']

    def verify_remote(state):
        remote_names = [asset['name'] for asset in state['assets']]
        if sorted(remote_names) != sorted(names):
            raise ValueError('Remote asset inventory mismatch.')
        with tempfile.TemporaryDirectory(prefix='windows-release-', dir=os.environ.get('RUNNER_TEMP') or os.environ.get('TMPDIR')) as temporary:
            gh('release', 'download', tag, '--repo', repository, '--dir', temporary)
            result = validate(temporary, expected)
            if result != local:
                raise ValueError('Remote release differs from local package.')
            for name in names:
                if digest(Path(temporary) / name) != digest(Path(directory) / name):
                    raise ValueError('Remote bytes differ from local package.')
            return result

    current = view()
    if current is not None and not current['isDraft']:
        if current['targetCommitish'] != expected['sha'] or tag_commit() != expected['sha'] or not current['isPrerelease']:
            raise ValueError('Published release commit/tag/preview state mismatch.')
        return verify_remote(current)
    existing = tag_commit(optional=True)
    if existing is not None and existing != expected['sha']:
        raise ValueError('Existing tag points at another commit.')
    if current is None:
        gh('release', 'create', tag, '--repo', repository, '--target', expected['sha'], '--draft', '--prerelease', '--latest=false', '--title', f"Music Windows {expected['version']} (unsigned preview)", '--notes', f"Unsigned Windows x64 preview. Commit: {expected['sha']}. Windows may warn about the unsigned installer. Verify SHA256SUMS before installation.")
        current = view()
    if current is None or not current['isDraft'] or current['targetCommitish'] != expected['sha'] or not current['isPrerelease']:
        raise ValueError('Draft does not target the exact commit/preview policy.')
    gh('release', 'upload', tag, *[str(Path(directory) / name) for name in names], '--repo', repository, '--clobber')
    current = view()
    if current is None or not current['isDraft'] or current['targetCommitish'] != expected['sha'] or not current['isPrerelease']:
        raise ValueError('Draft changed during upload.')
    verify_remote(current)
    gh('release', 'edit', tag, '--repo', repository, '--draft=false', '--prerelease', '--latest=false')
    current = view()
    if current is None or current['isDraft'] or not current['isPrerelease'] or current['targetCommitish'] != expected['sha'] or tag_commit() != expected['sha']:
        raise ValueError('Published release state/commit verification failed.')
    return verify_remote(current)


def version(root):
    def git(*args):
        result = subprocess.run(['git', '-C', str(root), *args], capture_output=True, text=True)
        if result.returncode:
            raise ValueError('A committed HEAD and full Git history are required.')
        return result.stdout.strip()
    if git('rev-parse', '--is-shallow-repository') != 'false':
        raise ValueError('Shallow checkout: fetch full history.')
    count = int(git('rev-list', '--count', 'HEAD'))
    if not 1 <= count <= 65534:
        raise ValueError('Commit count exceeds Windows AssemblyVersion range 1..65534.')
    name = f'0.1.{count}'
    return {'count': count, 'version': name, 'tag': f'windows-v{name}', 'sha': git('rev-parse', 'HEAD')}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['version', 'manifest', 'validate', 'publish'])
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--directory', type=Path, default=Path('dist/windows'))
    parser.add_argument('--repository', default=os.environ.get('GITHUB_REPOSITORY'))
    parser.add_argument('--github-output', type=Path)
    args = parser.parse_args()
    if args.command == 'publish':
        if not args.repository:
            parser.error('--repository is required for publication')
        result = publish(args.root, args.directory, args.repository)
    else:
        info = version(args.root)
        if args.command == 'version':
            result = info
            if args.github_output:
                with args.github_output.open('a', encoding='utf-8') as stream:
                    for key, value in info.items():
                        stream.write(f'{key}={value}\n')
        else:
            result = globals()[args.command](args.directory, info)
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
