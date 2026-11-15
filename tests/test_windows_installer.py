"""Unit tests for the Windows-only installer build orchestrator (not a Windows runtime test)."""
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
PATH = ROOT / 'scripts/build-windows-installer.py'
spec = importlib.util.spec_from_file_location('windows_installer', PATH)
assert spec is not None and spec.loader is not None
tool = importlib.util.module_from_spec(spec)
if PATH.exists():
    spec.loader.exec_module(tool)


class InstallerBuildTests(unittest.TestCase):
    def test_publish_plan_is_native_self_contained_and_matches_version(self):
        self.assertTrue(hasattr(tool, 'build_plan'), 'installer build planning is not implemented')
        info = {'version':'0.1.42', 'tag':'windows-v0.1.42', 'sha':'a'*40, 'count':42}
        plan = tool.build_plan(ROOT, ROOT / 'dist/windows/test-fixture', info)
        publish = next(command for command in plan if command[:2] == ['dotnet','publish'])
        self.assertIn('win-x64', publish)
        self.assertIn('--self-contained', publish)
        self.assertIn('-p:Version=0.1.42', publish)
        self.assertIn('-p:InformationalVersion=0.1.42+'+'a'*40, publish)
        self.assertFalse(any('WindowsAppSDKSelfContained=false' in part or 'PrepareForRunDependsOn' in part for command in plan for part in command))
        pack = next(command for command in plan if 'pack' in command)
        for value in ['MusicWindows', 'win-x64', 'Music.exe', '0.1.42', 'StartMenuRoot', '--noPortable']:
            self.assertIn(value, pack)
        self.assertNotIn('--skipVeloAppCheck', pack)
        self.assertNotIn('--signTemplate', pack)
        self.assertNotIn('Startup', pack)
        self.assertEqual(pack[pack.index('--delta')+1], 'None')

    def test_pack_plan_runs_checks_before_publish_and_uses_pinned_tool(self):
        self.assertTrue(hasattr(tool,'build_plan'))
        info = {'version':'0.1.42', 'tag':'windows-v0.1.42', 'sha':'b'*40, 'count':42}
        plan = tool.build_plan(ROOT, ROOT / 'dist/windows/test-fixture', info)
        self.assertEqual(plan[0][:3], ['dotnet','tool','restore'])
        self.assertTrue(any('Music.Core.Tests.csproj' in ' '.join(command) for command in plan[:-2]))
        self.assertTrue(any('LogicChecks.csproj' in ' '.join(command) for command in plan[:-2]))
        manifest = json.loads((ROOT/'apps/windows/.config/dotnet-tools.json').read_text())
        self.assertEqual(manifest['tools']['vpk']['version'], '1.2.161')

    def test_refuses_non_windows_before_running_commands(self):
        self.assertTrue(hasattr(tool,'build'))
        calls=[]
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(RuntimeError, 'Windows'):
                tool.build(ROOT, Path(directory)/'out', allow_unsigned=True, host='posix', run=lambda *a,**k:calls.append(a))
        self.assertEqual(calls, [])

    def test_unsigned_build_requires_explicit_acknowledgment(self):
        self.assertTrue(hasattr(tool,'build'))
        calls=[]
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, 'unsigned'):
                tool.build(ROOT, Path(directory)/'out', allow_unsigned=False, host='nt', run=lambda *a,**k:calls.append(a))
        self.assertEqual(calls, [])

    def test_dirty_release_inputs_are_rejected(self):
        self.assertTrue(hasattr(tool,'require_clean_sources'))
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            subprocess.run(['git','init','-q',str(root)],check=True)
            subprocess.run(['git','-C',str(root),'config','user.name','Fixture'],check=True)
            subprocess.run(['git','-C',str(root),'config','user.email','fixture@example.invalid'],check=True)
            source=root/'apps/windows/fixture.cs'
            source.parent.mkdir(parents=True)
            source.write_text('// explicit test fixture\n')
            subprocess.run(['git','-C',str(root),'add','.'],check=True)
            subprocess.run(['git','-C',str(root),'commit','-qm','fixture'],check=True)
            tool.require_clean_sources(root)
            source.write_text('// changed test fixture\n')
            with self.assertRaisesRegex(ValueError,'committed|clean'):
                tool.require_clean_sources(root)

    def test_payload_rejects_incomplete_publish(self):
        self.assertTrue(hasattr(tool,'validate_payload'))
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError,'Music.exe|payload'):
                tool.validate_payload(Path(directory))


if __name__ == '__main__':
    unittest.main()
