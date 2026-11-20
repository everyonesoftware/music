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
test('commit details abbreviate displayed commit IDs and source range to seven characters', () => {
  assert.match(panel, /NoteText\(\$"\{changes\.BaseRevision\[\.\.7\]\}…\{changes\.HeadRevision\[\.\.7\]\}"\)/);
  assert.match(panel, /NoteText\(\$"\{commit\.Sha\[\.\.7\]\}  \{commit\.Subject\}"\)/);
});
