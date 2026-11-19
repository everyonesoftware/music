import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const read = file => readFileSync(new URL(file, import.meta.url), 'utf8').replaceAll('\r\n', '\n');
const shell = read('../Music.App/Shell.xaml');
const panel = read('../Music.App/WindowsUpdatesPanel.cs');
test('Windows update offer includes collapsed on-demand notes and nested commit details', () => {
  assert.match(shell, /<Expander[^>]*x:Name="UpdateChangelog"[^>]*IsExpanded="False"/);
  assert.match(shell, /x:Name="UpdateOfferedVersion"/);
  assert.match(shell, /x:Name="RetryChangelogButton"/);
  assert.match(panel, /changelog\.Expanding \+= ChangelogExpanding/);
  assert.match(panel, /changelog\.Expanding -= ChangelogExpanding/);
  assert.match(panel, /new Expander\s*\{[^}]*Header = "Commit details"/s);
  assert.doesNotMatch(panel, /ReleaseNotesMarkdown|ReleaseNotesHtml/);
});
test('notes bind to the offered package and remain visible after download', () => {
  assert.match(panel, /notes\.SetOffer\(state.Version\)/);
  assert.match(panel, /new WindowsChangelogClient\(\)/);
  assert.match(panel, /await notes\.LoadAsync\(\)/);
  assert.match(panel, /notes\.Dispose\(\)/);
});
