import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import unittest
import zipfile
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / 'scripts/windows-release.py'
spec = importlib.util.spec_from_file_location('windows_release', SCRIPT)
assert spec is not None and spec.loader is not None
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)

class Tests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=os.environ.get('TMPDIR'))
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / 'repo'
        self.root.mkdir()
        self.git('init', '--initial-branch=main')
        self.git('-c', 'user.name=Fixture', '-c', 'user.email=test@example.invalid', 'commit', '--allow-empty', '-m', 'fixture')

    def git(self, *args):
        return subprocess.run(['git', '-C', str(self.root), *args], check=True, capture_output=True, text=True).stdout.strip()

    def test_version_uses_committed_history_and_windows_only_tag(self):
        self.assertIsNotNone(release, 'Windows release module is missing')
        self.assertEqual({'count': 1, 'version': '0.1.1', 'tag': 'windows-v0.1.1', 'sha': self.git('rev-parse', 'HEAD')}, release.version(self.root))

    @staticmethod
    def pe_fixture():
        data = bytearray(128)
        data[:2] = b'MZ'
        struct.pack_into('<I', data, 60, 64)
        data[64:68] = b'PE\0\0'
        struct.pack_into('<H', data, 68, 0x8664)
        return bytes(data)

    def rewrite_package(self, *, remove=None, updates=None):
        path = self.output/'MusicWindows-0.1.1-win-x64-full.nupkg'
        with zipfile.ZipFile(path) as archive:
            entries = {name:archive.read(name) for name in archive.namelist() if name != remove}
        entries.update(updates or {})
        with zipfile.ZipFile(path,'w') as archive:
            for name,value in entries.items(): archive.writestr(name,value)
        feedpath = self.output/'releases.win-x64.json'
        feed = json.loads(feedpath.read_text())
        feed['Assets'][0].update(Size=path.stat().st_size,SHA1=release.digest(path,'sha1'),SHA256=release.digest(path))
        feedpath.write_text(json.dumps(feed))
        (self.output/'RELEASES-win-x64').write_text(f"{release.digest(path,'sha1')} {path.name} {path.stat().st_size}\n")

    def test_package_requires_native_windows_runtime(self):
        self.fixture()
        self.rewrite_package(remove='lib/app/Microsoft.UI.Xaml.dll')
        with self.assertRaisesRegex(ValueError, 'payload|runtime|Xaml'):
            release.manifest(self.output,self.info)

    def test_real_vpk_utf8_bom_in_legacy_feed_is_supported(self):
        output = self.fixture()
        legacy = output / 'RELEASES-win-x64'
        legacy.write_text(legacy.read_text(), encoding='utf-8-sig')
        self.assertEqual('unsigned-preview', release.manifest(output, self.info)['signing'])

    def test_package_rejects_framework_dependent_dotnet(self):
        self.fixture()
        self.rewrite_package(updates={'lib/app/Music.runtimeconfig.json':json.dumps({'runtimeOptions':{'framework':{'name':'Microsoft.NETCore.App','version':'10.0.15'}}})})
        with self.assertRaisesRegex(ValueError, 'self-contained|runtime'):
            release.manifest(self.output,self.info)

    def fixture(self):
        self.output = Path(self.temp.name) / 'output'
        self.output.mkdir()
        self.info = release.version(self.root)
        name = 'MusicWindows-0.1.1-win-x64-full.nupkg'
        # Structural fixture only, not a runnable installer or application.
        (self.output / 'MusicWindows-win-x64-Setup.exe').write_bytes(self.pe_fixture())
        with zipfile.ZipFile(self.output / name, 'w') as package:
            package.writestr('MusicWindows.nuspec', '<package><metadata><id>MusicWindows</id><version>0.1.1</version><channel>win-x64</channel><mainExe>Music.exe</mainExe><os>win</os><rid>win-x64</rid></metadata></package>')
            package.writestr('lib/app/Music.exe', self.pe_fixture())
            for filename in ['Music.dll', 'coreclr.dll', 'hostfxr.dll', 'Microsoft.UI.Xaml.dll', 'Microsoft.WindowsAppRuntime.dll']:
                package.writestr('lib/app/'+filename, b'explicit non-runnable unit fixture')
            package.writestr('lib/app/Music.runtimeconfig.json', json.dumps({'runtimeOptions':{'includedFrameworks':[{'name':'Microsoft.NETCore.App','version':'10.0.15'}]}}))
            package.writestr('lib/app/Music.deps.json', json.dumps({'runtimeTarget':{'name':'.NETCoreApp,Version=v10.0/win-x64'},'libraries':{'Music/0.1.1':{}}}))
        data = (self.output / name).read_bytes()
        feed = {'Assets': [{'PackageId': 'MusicWindows', 'Version': '0.1.1', 'Type': 'Full', 'FileName': name, 'Size': len(data), 'SHA1': hashlib.sha1(data).hexdigest(), 'SHA256': hashlib.sha256(data).hexdigest()}]}
        (self.output / 'releases.win-x64.json').write_text(json.dumps(feed))
        (self.output / 'assets.win-x64.json').write_text(json.dumps([{'RelativeFileName': 'MusicWindows-win-x64-Setup.exe', 'Type': 'Installer'}, {'RelativeFileName': name, 'Type': 'Full'}]))
        (self.output / 'RELEASES-win-x64').write_text(f"{hashlib.sha1(data).hexdigest().upper()} {name} {len(data)}\n")
        return self.output

    def test_manifest_roundtrip_checks_real_nupkg_and_feed(self):
        output = self.fixture()
        self.assertTrue(hasattr(release, 'manifest'), 'manifest API missing')
        result = release.manifest(output, self.info)
        self.assertEqual('unsigned-preview', result['signing'])
        self.assertEqual('windows-x64', result['platform'])
        self.assertEqual(5, len(result['artifacts']))
        self.assertEqual(result, release.validate(output, self.info))
        self.assertTrue((output / 'SHA256SUMS').is_file())

    def test_unlisted_symlink_missing_and_unsafe_assets_fail_closed(self):
        output = self.fixture()
        release.manifest(output, self.info)
        for name in ['unlisted.exe', 'nested']:
            path = output / name
            path.write_bytes(b'extra')
            with self.assertRaises(ValueError):
                release.validate(output, self.info)
            path.unlink()
        setup = output / 'MusicWindows-win-x64-Setup.exe'
        setup.unlink()
        try:
            setup.symlink_to(output / 'releases.win-x64.json')
        except OSError:
            self.skipTest('This Windows test account cannot create symlinks.')
        with self.assertRaises(ValueError):
            release.validate(output, self.info)

    def fake_github(self):
        remote = Path(self.temp.name) / 'remote'
        remote.mkdir()
        state = {'release': None, 'calls': [], 'wrong_tag': False, 'tamper': False}
        def run(args, **kwargs):
            state['calls'].append(args)
            data = None
            if args[1:3] == ['release', 'view']:
                if state['release'] is None:
                    return subprocess.CompletedProcess(args, 1, '', 'release not found')
                data = dict(state['release'], assets=[{'name': p.name} for p in remote.iterdir()])
            elif args[1:3] == ['release', 'create']:
                state['release'] = {'isDraft': True, 'isPrerelease': True, 'targetCommitish': self.info['sha']}
            elif args[1:3] == ['release', 'upload']:
                for filename in args[4:args.index('--repo')]:
                    shutil.copy2(filename, remote / Path(filename).name)
            elif args[1:3] == ['release', 'download']:
                for path in remote.iterdir():
                    shutil.copy2(path, Path(args[args.index('--dir') + 1]) / path.name)
                if state['tamper']:
                    (Path(args[args.index('--dir') + 1]) / 'MusicWindows-win-x64-Setup.exe').write_bytes(b'bad')
            elif args[1:3] == ['release', 'edit']:
                state['release']['isDraft'] = False
            elif args[1] == 'api':
                if state['release'] is None or state['release']['isDraft']:
                    return subprocess.CompletedProcess(args, 1, '', 'HTTP 404')
                data = {'object': {'type': 'commit', 'sha': '0' * 40 if state['wrong_tag'] else self.info['sha']}}
            else:
                raise AssertionError(args)
            return subprocess.CompletedProcess(args, 0, json.dumps(data), '')
        return state, run

    def test_publish_stages_verifies_then_publishes_preview_without_touching_latest(self):
        output = self.fixture()
        local = release.manifest(output, self.info)
        state, run = self.fake_github()
        self.assertTrue(hasattr(release, 'publish'), 'publish API missing')
        self.assertEqual(local, release.publish(self.root, output, 'fixture/music', run=run))
        mutations = [a for a in state['calls'] if a[1:3] in [['release', 'create'], ['release', 'edit']]]
        self.assertEqual(2, len(mutations))
        for args in mutations:
            self.assertIn('--latest=false', args)
            self.assertIn('--prerelease', args)
        state['calls'].clear()
        self.assertEqual(local, release.publish(self.root, output, 'fixture/music', run=run))
        self.assertFalse(any(a[1:3] in [['release', 'upload'], ['release', 'edit'], ['release', 'create']] for a in state['calls']))

    def test_metadata_cannot_claim_signing_or_escape_version_namespace(self):
        output = self.fixture()
        with self.assertRaisesRegex(ValueError, 'sign'):
            release.manifest(output, self.info, signed=True)
        bad = dict(self.info, tag='v0.1.1')
        with self.assertRaises(ValueError):
            release.manifest(output, bad)
        bad = dict(self.info, version='../0.1.1')
        with self.assertRaises(ValueError):
            release.manifest(output, bad)

    def test_cli_version_and_manifest_validate_are_exercised(self):
        output = self.fixture()
        command = ['python', str(SCRIPT), 'version', '--root', str(self.root)]
        result = subprocess.run(command, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(result.stdout.strip(), 'CLI must emit JSON')
        self.assertEqual(self.info, json.loads(result.stdout))
        for action in ['manifest', 'validate']:
            result = subprocess.run(['python', str(SCRIPT), action, '--root', str(self.root), '--directory', str(output)], capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(self.info['sha'], json.loads(result.stdout)['sha'])

    def test_shallow_and_unborn_history_fail(self):
        clone = Path(self.temp.name) / 'shallow'
        subprocess.run(['git', 'clone', '--no-local', '--depth=1', str(self.root), str(clone)], check=True, capture_output=True)
        with self.assertRaisesRegex(ValueError, 'Shallow'):
            release.version(clone)
        unborn = Path(self.temp.name) / 'unborn'
        subprocess.run(['git', 'init', str(unborn)], check=True, capture_output=True)
        with self.assertRaisesRegex(ValueError, 'HEAD'):
            release.version(unborn)

    def test_assembly_version_count_limit(self):
        def run(args, **kwargs):
            value = 'false' if '--is-shallow-repository' in args else '65535'
            return subprocess.CompletedProcess(args, 0, value, '')
        with patch.object(release.subprocess, 'run', run):
            with self.assertRaisesRegex(ValueError, '65534'):
                release.version(self.root)

    def test_asset_tampering_and_checksum_tampering_fail(self):
        output = self.fixture()
        release.manifest(output, self.info)
        setup = output / 'MusicWindows-win-x64-Setup.exe'
        original = setup.read_bytes()
        setup.write_bytes(b'MZ-replaced-fixture')
        with self.assertRaises(ValueError):
            release.validate(output, self.info)
        setup.write_bytes(original)
        (output / 'SHA256SUMS').write_text('bad checksum')
        with self.assertRaises(ValueError):
            release.validate(output, self.info)

    def test_feed_wrong_hash_version_and_path_are_rejected(self):
        output = self.fixture()
        path = output / 'releases.win-x64.json'
        original = json.loads(path.read_text())
        for key, value in [('SHA256', '0' * 64), ('Version', '0.1.2'), ('FileName', '../escape.nupkg'), ('PackageId', 'Other')]:
            feed = json.loads(json.dumps(original))
            feed['Assets'][0][key] = value
            path.write_text(json.dumps(feed))
            with self.assertRaises(ValueError):
                release.manifest(output, self.info)
        path.write_text(json.dumps(original))

    def test_nuspec_version_must_match_feed(self):
        output = self.fixture()
        package = output / 'MusicWindows-0.1.1-win-x64-full.nupkg'
        with zipfile.ZipFile(package, 'w') as archive:
            archive.writestr('MusicWindows.nuspec', '<package><metadata><id>MusicWindows</id><version>0.1.2</version></metadata></package>')
        path = output / 'releases.win-x64.json'
        feed = json.loads(path.read_text())
        feed['Assets'][0].update(Size=package.stat().st_size, SHA1=release.digest(package, 'sha1'), SHA256=release.digest(package))
        path.write_text(json.dumps(feed))
        (output / 'RELEASES-win-x64').write_text(f"{release.digest(package, 'sha1')} {package.name} {package.stat().st_size}\n")
        with self.assertRaisesRegex(ValueError, 'NuGet'):
            release.manifest(output, self.info)

    def test_published_release_tag_mismatch_cannot_mutate(self):
        output = self.fixture()
        release.manifest(output, self.info)
        state, run = self.fake_github()
        release.publish(self.root, output, 'fixture/music', run=run)
        state['calls'].clear()
        state['wrong_tag'] = True
        with self.assertRaises(ValueError):
            release.publish(self.root, output, 'fixture/music', run=run)
        self.assertFalse(any(a[1:3] in [['release', 'upload'], ['release', 'edit'], ['release', 'create']] for a in state['calls']))

    def test_remote_tampering_leaves_draft_unpublished(self):
        output = self.fixture()
        release.manifest(output, self.info)
        state, run = self.fake_github()
        state['tamper'] = True
        with self.assertRaises(ValueError):
            release.publish(self.root, output, 'fixture/music', run=run)
        self.assertTrue(state['release']['isDraft'])
        self.assertFalse(any(a[1:3] == ['release', 'edit'] for a in state['calls']))

    def test_authentication_and_network_errors_never_create_release(self):
        output = self.fixture()
        release.manifest(output, self.info)
        for error in ['HTTP 401 authentication required', 'connection timed out', 'HTTP 403 forbidden']:
            calls = []
            def run(args, **kwargs):
                calls.append(args)
                return subprocess.CompletedProcess(args, 1, '', error)
            with self.assertRaises(RuntimeError):
                release.publish(self.root, output, 'fixture/music', run=run)
            self.assertEqual(1, len(calls))

    def test_published_remote_bytes_must_equal_local_not_just_validate(self):
        output = self.fixture()
        release.manifest(output, self.info)
        state, run = self.fake_github()
        release.publish(self.root, output, 'fixture/music', run=run)
        (output / 'MusicWindows-win-x64-Setup.exe').write_bytes(self.pe_fixture()+b'new valid unit fixture trailer')
        release.manifest(output, self.info)
        state['calls'].clear()
        with self.assertRaisesRegex(ValueError, 'differs'):
            release.publish(self.root, output, 'fixture/music', run=run)
        self.assertFalse(any(a[1:3] == ['release', 'upload'] for a in state['calls']))

    def test_vpk_asset_index_and_legacy_feed_must_match_package(self):
        output = self.fixture()
        index = output / 'assets.win-x64.json'
        original = index.read_text()
        index.write_text(json.dumps([{'RelativeFileName': '../escape.exe', 'Type': 'Installer'}]))
        with self.assertRaisesRegex(ValueError, 'index'):
            release.manifest(output, self.info)
        index.write_text(original)
        (output / 'RELEASES-win-x64').write_text('0' * 40 + ' MusicWindows-0.1.1-win-x64-full.nupkg 99\n')
        with self.assertRaisesRegex(ValueError, 'legacy'):
            release.manifest(output, self.info)

    def test_dirty_source_cannot_reach_github(self):
        output = self.fixture()
        release.manifest(output, self.info)
        (self.root / 'uncommitted.cs').write_text('// dirty source fixture')
        calls = []
        def run(args, **kwargs):
            calls.append(args)
            raise AssertionError('Dirty source must not reach GitHub')
        with self.assertRaisesRegex(ValueError, 'clean|dirty'):
            release.publish(self.root, output, 'fixture/music', run=run)
        self.assertEqual([], calls)

if __name__ == '__main__':
    unittest.main()
