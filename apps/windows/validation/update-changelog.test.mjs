import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const read = file => readFileSync(new URL(file, import.meta.url), 'utf8').replaceAll('\r\n', '\n');
const shell = read('../Music.App/Shell.xaml');
const panel = read('../Music.App/WindowsUpdatesPanel.cs');
const backend = read('../Music.App/WindowsUpdateBackend.cs');
const client = read('../Music.App/WindowsChangelogClient.cs');
test('Windows update offer keeps collapsed notes and nested commit details', () => {
  assert.match(shell, /<Expander[^>]*x:Name="UpdateChangelog"[^>]*IsExpanded="False"/);
  assert.match(shell, /x:Name="UpdateOfferedVersion"/);
  assert.match(shell, /x:Name="RetryChangelogButton"/);
  assert.match(panel, /new Expander\s*\{[^}]*Header = "Commit details"/s);
  assert.doesNotMatch(panel, /ReleaseNotesMarkdown|ReleaseNotesHtml/);
});
test('notes are delivered with check status; disclosure cannot start network work', () => {
  assert.match(panel, /notes\.SetOffer\(state.Version, state.Changelog\)/);
  assert.match(panel, /new WindowsChangelogController\(\)/);
  assert.doesNotMatch(panel, /WindowsChangelogClient|ChangelogExpanding|notes\.LoadAsync|Assembly.GetExecutingAssembly/);
  assert.match(panel, /RetryNotesClicked[^\n]*await controller.CheckAsync\(\)/);
  assert.match(panel, /notes\.Dispose\(\)/);
  assert.match(backend, /Changelog = source.GetChangelog\(CurrentVersion, update.TargetFullRelease\)/);
  assert.match(backend, /new UpdateManager\(source,/);
  assert.doesNotMatch(client, /HttpClient|compare\/|commits\/|IsRelevant|Regex.Replace/);
});
test('initial status and retry copy describe a new update check, not disclosure fetching', () => {
  assert.match(shell, /x:Name="ChangelogStatus" Text="Check for updates to load changes\."/);
  assert.match(shell, /x:Name="RetryChangelogButton" Content="Check again"/);
  assert.doesNotMatch(panel, /Expand to load changes/);
});
test('commit details use one native compare link and individual commit links with full GitHub identities', () => {
  assert.match(panel, /details\.Children\.Add\(CommitLink\(\$"\{changes\.BaseRevision\[\.\.7\]\}…\{changes\.HeadRevision\[\.\.7\]\}"/);
  assert.match(panel, /\$"https:\/\/github\.com\/everyonesoftware\/music\/compare\/\{changes\.BaseRevision\}\.\.\.\{changes\.HeadRevision\}"/);
  assert.match(panel, /CommitLink\(commit\.Sha\[\.\.7\],\s*\$"https:\/\/github\.com\/everyonesoftware\/music\/commit\/\{commit\.Sha\}"/);
  assert.match(panel, /var subject = NoteText\(commit\.Subject\)/);
  assert.match(panel, /row\.Children\.Add\(subject\)/);
  assert.match(panel, /new Grid \{ ColumnSpacing = 9 \}/);
  assert.match(panel, /ColumnDefinition \{ Width = GridLength\.Auto \}/);
  assert.match(panel, /Grid\.SetColumn\(subject, 1\)/);
  assert.match(panel, /private HyperlinkButton CommitLink\(/);
  assert.match(panel, /NavigateUri = new Uri\(uri\)/);
  assert.match(panel, /Content = text/);
  assert.match(panel, /AutomationProperties\.SetName\(link, accessibleName\)/);
  assert.match(panel, /ToolTipService\.SetToolTip\(link, accessibleName\)/);
  assert.match(panel, /Compare source range.*on GitHub \(opens in your browser\)/);
  assert.match(panel, /View commit.*on GitHub \(opens in your browser\)/);
  // NavigateUri delegates activation to the native keyboard-accessible hyperlink.
  // Merely constructing/reopening either disclosure must never launch or fetch.
  assert.doesNotMatch(panel, /LaunchUriAsync|HttpClient|Process\.Start|\.Click\s*\+=.*[Ll]ink|\.Expanding\s*\+=/);
});
test('commit links use scoped theme-aware dark boxes with native hover and keyboard focus', () => {
  const resources = shell.match(/<StackPanel x:Name="ChangelogContent"[^>]*>\s*<StackPanel.Resources>([\s\S]*?)<\/StackPanel.Resources>/)?.[1] ?? '';
  assert.ok(resources, 'link resources must be scoped to the changelog, not all shell links');
  const style = resources.match(/<Style x:Key="CommitLinkStyle" TargetType="HyperlinkButton">([\s\S]*?)<\/Style>/)?.[1] ?? '';
  for (const [property, value] of [
    ['FontFamily', 'Consolas'], ['FontSize', '14'], ['CornerRadius', '4'],
    ['BorderThickness', '1'], ['Padding', '6,0'], ['MinWidth', '0'], ['MinHeight', '24'],
    ['HorizontalAlignment', 'Left'], ['VerticalAlignment', 'Top'],
    ['IsTabStop', 'True'], ['UseSystemFocusVisuals', 'True'],
    ['Background', '{ThemeResource HyperlinkButtonBackground}'],
    ['BorderBrush', '{ThemeResource HyperlinkButtonBorderBrush}'],
    ['Foreground', '{ThemeResource HyperlinkButtonForeground}'],
  ]) assert.ok(style.includes(`Property="${property}" Value="${value}"`), `${property} = ${value}`);
  assert.doesNotMatch(style, /Property="Template"/); // Keep pinned native hyperlink semantics and states.
  const theme = key => resources.match(new RegExp(`<ResourceDictionary x:Key="${key}">([\\s\\S]*?)</ResourceDictionary>`))?.[1] ?? '';
  const dark = theme('Default');
  for (const [key, color] of [
    ['HyperlinkButtonBackground', '#2a312c'], ['HyperlinkButtonBorderBrush', '#545e56'],
    ['HyperlinkButtonForeground', '#bbd8c1'],
    ['HyperlinkButtonBackgroundPointerOver', '#34483a'], ['HyperlinkButtonBorderBrushPointerOver', '#8daf96'],
  ]) assert.ok(dark.includes(`x:Key="${key}" Color="${color}"`), `dark ${key}`);
  for (const name of ['Default', 'Light', 'HighContrast']) {
    const dictionary = theme(name);
    for (const state of ['', 'PointerOver', 'Pressed', 'Disabled']) {
      for (const part of ['Background', 'Foreground', 'BorderBrush']) {
        assert.ok(dictionary.includes(`x:Key="HyperlinkButton${part}${state}"`), `${name} ${part}${state}`);
      }
    }
  }
  assert.match(theme('HighContrast'), /\{ThemeResource SystemColorHotlightColor\}/);
  assert.match(theme('HighContrast'), /\{ThemeResource SystemColorWindowColor\}/);
  assert.match(panel, /Style = \(Style\)notesContent.Resources\["CommitLinkStyle"\]/);
});
