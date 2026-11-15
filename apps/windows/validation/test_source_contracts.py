"""Source contracts only: does not claim to execute the Windows XAML loader."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

BASE = Path(__file__).resolve().parents[1] / 'Music.App'
XAML = '{http://schemas.microsoft.com/winfx/2006/xaml}'

class SourceContracts(unittest.TestCase):
    def test_installer_hooks_run_before_windows_initialization(self):
        startup = (BASE / 'Program.cs').read_text()
        self.assertIn('VelopackApp.Build()', startup)
        self.assertIn('.SetAutoApplyOnStartup(false).Run()', startup)
        self.assertLess(startup.index('VelopackApp.Build()'), startup.index('WinRT.ComWrappersSupport.InitializeComWrappers()'))
        project = ET.parse(BASE / 'Music.App.csproj').getroot()
        packages = {element.get('Include'): element.get('Version') for element in project.iter('PackageReference')}
        self.assertEqual(packages.get('Velopack'), '1.2.161')

    def test_reconnect_migrates_session_before_committing_profile(self):
        source = (BASE / 'MainWindow.cs').read_text()
        connect = source.split('private async Task ConnectAsync', 1)[1].split('private void SetConnecting', 1)[0]
        self.assertIn('ConnectionIdentity.SameAccount', connect)
        self.assertIn('snapshot with { ConnectionId = profile.Id }', connect)
        self.assertLess(connect.index('sessions.SavePlayback'), connect.index('profiles.Commit'))
        self.assertLess(connect.index('ActivateConnection'), connect.index('RestorePlayback()'))

    def test_pagination_cannot_supersede_a_replacement_search(self):
        source = (BASE / 'MainWindow.cs').read_text()
        replace = source.split('private async Task LoadBrowseAsync', 1)[1].split('private void RenderBrowse', 1)[0]
        self.assertIn('browsePending = true', replace)
        self.assertLess(replace.index('browsePending = true'), replace.index('await'))
        self.assertIn('browseResultsVersion++', replace)
        paginate = source.split('private void AddLoadMore', 1)[1].split('private string detailTitle', 1)[0]
        self.assertIn('string renderedQuery = query', paginate)
        self.assertIn('int renderedOffset = songOffset', paginate)
        self.assertIn('long renderedVersion = browseResultsVersion', paginate)
        guard = paginate.split('var lease = browseGate.Begin()', 1)[0]
        self.assertIn('browsePending', guard)
        self.assertIn('renderedVersion != browseResultsVersion', guard)
        self.assertIn('renderedQuery != query', guard)
        self.assertIn('renderedOffset != songOffset', guard)
        self.assertIn('SearchSongsAsync(renderedQuery, renderedOffset', paginate)

    def test_connection_switch_invalidates_rendered_controls(self):
        source = (BASE / 'MainWindow.cs').read_text()
        activate = source.split('private void ActivateConnection', 1)[1].split('private async Task TestConnectionAsync', 1)[0]
        self.assertIn('connectionGate.Begin()', activate)
        self.assertIn('C<StackPanel>("BrowsePanel").Children.Clear()', activate)
        self.assertIn('detailSongs.Clear()', activate)
        for method, following in [('private void AddCollection', 'private FrameworkElement CardElement'), ('private void AddSongs', 'private void AddLoadMore')]:
            body = source.split(method, 1)[1].split(following, 1)[0]
            self.assertIn('var owner = connectionLease', body)
            self.assertIn('connectionGate.IsCurrent(owner)', body)

    def test_runtime_xaml_has_names_for_every_typed_control_lookup(self):
        root = ET.parse(BASE / 'Shell.xaml').getroot()
        names = {element.get(XAML + 'Name'): element.tag.split('}')[-1] for element in root.iter() if element.get(XAML + 'Name')}
        source = (BASE / 'MainWindow.cs').read_text()
        for control_type, name in re.findall(r'C<(\w+)>\("([^"]+)"\)', source):
            with self.subTest(name=name):
                self.assertEqual(names.get(name), control_type)
        named = [element.get(XAML + 'Name') for element in root.iter() if element.get(XAML + 'Name')]
        self.assertEqual(len(named), len(set(named)))

    def test_runtime_xaml_avoids_compiler_generated_features(self):
        text = (BASE / 'Shell.xaml').read_text()
        ET.fromstring(text)
        self.assertNotIn('x:Class', text)
        self.assertNotIn('x:Bind', text)
        for element in ET.fromstring(text).iter():
            for attribute in element.attrib:
                self.assertNotIn(attribute, ['Click', 'Loaded', 'TextChanged', 'ValueChanged'])

    def test_settings_are_blank_and_password_only_uses_vault(self):
        root = ET.parse(BASE / 'Shell.xaml').getroot()
        names = {element.get(XAML + 'Name'): element for element in root.iter()}
        for name in ['ConnectionName', 'ServerAddress', 'Username', 'Password']:
            self.assertNotIn('Text', names[name].attrib)
            self.assertNotIn('Password', names[name].attrib)
        profile = (BASE / 'ProfileStore.cs').read_text()
        self.assertIn('EveryoneSoftware.Music', profile)
        self.assertIn('new PasswordVault()', profile)
        self.assertIn('JsonSerializer.Serialize(profile)', profile)
        self.assertNotIn('Serialize(password)', profile)

    def test_project_preserves_native_windows_release_defaults(self):
        root = ET.parse(BASE / 'Music.App.csproj').getroot()
        props = {element.tag: element.text for group in root.findall('PropertyGroup') for element in group}
        for name, value in {'UseWinUI':'true', 'WindowsAppSDKSelfContained':'true', 'SelfContained':'true', 'WindowsPackageType':'None', 'RuntimeIdentifier':'win-x64', 'TargetFramework':'net10.0-windows10.0.19041.0'}.items():
            self.assertEqual(props[name], value)
        self.assertTrue(any(e.get('Include') == '../Music.Core/Music.Core.csproj' for e in root.iter('ProjectReference')))
        packages = {e.get('Include'): e.get('Version') for e in root.iter('PackageReference')}
        self.assertEqual(packages['Microsoft.WindowsAppSDK.WinUI'], '1.8.260803003')
        self.assertEqual(packages['CommunityToolkit.Mvvm'], '8.4.2')

    def test_artwork_uses_bounded_bytes_not_signed_ui_urls(self):
        source = (BASE / 'ArtworkLoader.cs').read_text()
        self.assertIn('new(4, 4)', source)
        self.assertIn('MaximumCacheBytes', source)
        self.assertIn('GetCoverArtAsync', source)
        self.assertIn('InMemoryRandomAccessStream', source)
        self.assertNotIn('UriSource', source)
        self.assertNotIn('GetStreamUri', source)

    def test_no_fake_connection_or_production_demo_assets(self):
        xaml = (BASE / 'Shell.xaml').read_text()
        for forbidden in ['Lavender Haze', 'Taylor Swift', 'demo-listener', 'music.example.com', 'Remember position', 'Your Collection', 'Now Playing']:
            self.assertNotIn(forbidden, xaml)
        self.assertIn('Connect your music server', xaml)
        self.assertIn('Automatic updates are not enabled yet', xaml)

if __name__ == '__main__':
    unittest.main(verbosity=2)
