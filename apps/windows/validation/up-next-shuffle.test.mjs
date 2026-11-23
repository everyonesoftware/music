import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { spawnSync } from 'node:child_process';
const read = name => readFileSync(new URL(`../Music.App/${name}`, import.meta.url), 'utf8').replaceAll('\r\n', '\n');
const main = read('MainWindow.cs'), shell = read('Shell.xaml');
const method = name => main.slice(main.indexOf(`private ${name}`), main.indexOf('\n    private ', main.indexOf(`private ${name}`) + 1));

test('Up next owns a fenced native one-shot icon Button, never a global shuffle toggle', () => {
  for (const file of ['MainWindow.cs', 'MainWindow.Offline.cs', 'MainWindow.CollectionTransport.cs', 'Shell.xaml'])
    assert.doesNotMatch(read(file), /ShuffleButton|VolumeSlider/);
  const heading = method('Grid CreateUpNextHeading()');
  assert.match(heading, /new Button/);
  assert.match(heading, /Symbol.Shuffle/);
  assert.match(heading, /Width = 48, Height = 48/);
  assert.match(heading, /HorizontalAlignment = HorizontalAlignment.Right/);
  assert.match(heading, /SetName\(shuffle, "Shuffle Up next"\)/);
  assert.match(heading, /SetToolTip\(shuffle, "Shuffle Up next"\)/);
  assert.match(heading, /CanShufflePending/);
  assert.match(heading, /ReferenceEquals\(capturedQueue, queue\)/);
  assert.match(heading, /ReferenceEquals\(order, playbackOrder\)/);
  assert.match(heading, /connectionGate.IsCurrent\(owner\)/);
  assert.match(heading, /closed \|\| accountChangePending/);
  assert.match(heading, /order.ShufflePending\(\)/);
  assert.match(heading, /SavePlayback\(\); ScheduleCache\(allowCurrentHandoff: false\); RenderQueue\(\)/);
  assert.match(read('MainWindow.Offline.cs'), /if \(allowCurrentHandoff\) HandoffCompletedSong\(song.Id, scheduledPlaybackVersion\)/);
  assert.doesNotMatch(heading, /SelectTrack|player\.|IsChecked|ToggleButton/);
  assert.match(main, /entry.Heading == "Up next".*CreateUpNextHeading\(\)/);
  assert.match(main, /RemainingOrder = playbackOrder\?\.RemainingOrder/);
  assert.match(main, /playbackOrder = PlaybackOrder.Restore\(saved\)/);
  assert.match(read('MainWindow.Offline.cs'), /new PlaybackOrder\(queue.Count, queueIndex, false, repeat\)/);
  assert.match(method('void RenderQueue()'), /else entries.Add\(new\(queue, Heading: "Up next"\)\)/);
  const constructor = main.slice(main.indexOf('public MainWindow()'), main.indexOf('private async Task InitializeAsync()'));
  assert.match(constructor, /RenderQueue\(\)/);
});

test('pending rows carry exact materialized offsets and select them before same-source handoff', () => {
  assert.match(main, /record QueueEntry\([^;]*int PendingOffset = -1/);
  assert.match(method('void RenderQueue()'), /RemainingOrder\.Select\(\(index, offset\) => new QueueEntry\(queue, index, PendingOffset: offset\)\)/);
  const click = method('void InitializeQueueList(ListView list)');
  assert.match(click, /entry\.PendingOffset >= 0/);
  assert.match(click, /SelectPending\(entry\.PendingOffset\)/);
  assert.ok(click.indexOf('SelectPending(entry.PendingOffset)') < click.indexOf('SelectTrack(entry.Index, true)'));
  const select = method('async void SelectTrack(int index, bool play, double position = 0)');
  assert.ok(select.indexOf('queueIndex = index') < select.indexOf('EnsurePlaybackOrder()'));
  assert.match(select, /playbackOrder!\.Select\(index\)/); // Active-source replacement stays same-visit.
});

test('real queue click and source selection preserve the exact pending occurrence through EnsurePlaybackOrder', () => {
  const initialize = method('void InitializeQueueList(ListView list)');
  const click = initialize.slice(initialize.indexOf('            if (closed || accountChangePending'), initialize.indexOf('\n        };', initialize.indexOf('        list.ItemClick +=')));
  const entry = main.match(/    private sealed record QueueEntry[^;]+;/)[0];
  const select = method('async void SelectTrack(int index, bool play, double position = 0)');
  const selection = select.slice(select.indexOf('            queueIndex = index;'), select.indexOf('            Song song ='));
  const offline = read('MainWindow.Offline.cs');
  const start = offline.indexOf('    private void EnsurePlaybackOrder()');
  const ensure = offline.slice(start, offline.indexOf('\n    private ', start + 1)).replaceAll('Microsoft.UI.Xaml.Controls.Primitives.ToggleButton', 'ToggleButton');
  const dir = mkdtempSync(join(tmpdir(), 'music-pending-click-'));
  try {
    const order = fileURLToPath(new URL('../Music.Core/PlaybackOrder.cs', import.meta.url));
    const models = fileURLToPath(new URL('../Music.Core/Models.cs', import.meta.url));
    writeFileSync(join(dir, 'Checks.csproj'), `<Project Sdk="Microsoft.NET.Sdk"><PropertyGroup><OutputType>Exe</OutputType><TargetFramework>net10.0</TargetFramework><ImplicitUsings>enable</ImplicitUsings><Nullable>enable</Nullable></PropertyGroup><ItemGroup><Compile Include="${order}"/><Compile Include="${models}"/></ItemGroup></Project>`);
    writeFileSync(join(dir, 'Program.cs'), `
using Music.Core;
Harness.Run();
class ToggleButton { public bool? IsChecked = true; }
class Args { public object? ClickedItem; }
class Harness {
    bool closed = false, accountChangePending = false;
    List<Song> queue = Enumerable.Range(0, 3).Select(i => new Song("duplicate", "Song", "Artist", "Album", null, 100)).ToList();
    int queueIndex; double restoredPosition;
    PlaybackOrder? playbackOrder;
    T C<T>(string name) where T : new() => new T();
    ${entry}
    ${ensure}
    void Click(Args args) { ${click} }
    void SelectTrack(int index, bool play, double position = 0) { ${selection} }
    static void Require(bool ok, string message) { if (!ok) throw new Exception(message); }
    public static void Run() {
        foreach (int offset in new[] { 3, 2 }) {
            var h = new Harness(); h.playbackOrder = new(3, 0, false, true);
            for (int i = 0; i < 3; i++) { h.playbackOrder.MarkStarted(); h.playbackOrder.MoveNext(); }
            h.playbackOrder.MarkStarted(); h.playbackOrder.MovePrevious(); h.playbackOrder.MovePrevious();
            h.queueIndex = h.playbackOrder.Current;
            var pending = h.playbackOrder.RemainingOrder.ToArray();
            h.playbackOrder.MarkStarted();
            h.Click(new Args { ClickedItem = new QueueEntry(h.queue, pending[offset], PendingOffset: offset) });
            Require(h.queueIndex == pending[offset], "native click did not select row index");
            Require(h.playbackOrder.RemainingOrder.SequenceEqual(pending.Skip(offset + 1)), "source selection consumed wrong pending occurrence");
            var played = h.playbackOrder.Played.ToArray();
            h.SelectTrack(h.queueIndex, false, 42);
            Require(h.playbackOrder.Played.SequenceEqual(played), "same-source handoff manufactured history");
            Require(h.playbackOrder.RemainingOrder.SequenceEqual(pending.Skip(offset + 1)), "handoff changed pending plan");
        }
        Console.WriteLine("Exact pending queue click: PASS");
    }
}`);
    const result = spawnSync('dotnet', ['run', '--project', join(dir, 'Checks.csproj'), '-c', 'Release'], { encoding: 'utf8', timeout: 120000 });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.match(result.stdout, /Exact pending queue click: PASS/);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('same-account reconnect refreshes cached shuffle availability and stale controls retain live guards', () => {
  const render = method('void RenderQueue()');
  const state = render.slice(render.indexOf('string state ='), render.indexOf('\n        if (ReferenceEquals'));
  const enabled = method('Grid CreateUpNextHeading()').match(/IsEnabled = ([^}]+) };/)[1];
  const heading = method('Grid CreateUpNextHeading()');
  const click = heading.slice(heading.indexOf('            if (closed'), heading.indexOf('\n        };'));
  const connect = method('async Task ConnectAsync()');
  const cleanup = connect.slice(connect.indexOf('            candidate?.Dispose();'), connect.lastIndexOf('\n        }'));
  assert.ok(state && enabled && cleanup && click, 'production fragments must be extracted');
  const dir = mkdtempSync(join(tmpdir(), 'music-reconnect-shuffle-'));
  try {
    const order = fileURLToPath(new URL('../Music.Core/PlaybackOrder.cs', import.meta.url));
    const models = fileURLToPath(new URL('../Music.Core/Models.cs', import.meta.url));
    writeFileSync(join(dir, 'Checks.csproj'), `<Project Sdk="Microsoft.NET.Sdk"><PropertyGroup><OutputType>Exe</OutputType><TargetFramework>net10.0</TargetFramework><ImplicitUsings>enable</ImplicitUsings><Nullable>enable</Nullable></PropertyGroup><ItemGroup><Compile Include="${order}"/><Compile Include="${models}"/></ItemGroup></Project>`);
    writeFileSync(join(dir, 'Program.cs'), `
using Music.Core;
Harness.Run();
class ToggleButton { public bool? IsChecked = false; }
class Gate { public bool Current = true; public bool IsCurrent(int owner) => Current; }
class Harness {
    bool closed = false, accountChangePending, ownsAccountChange = true;
    int cacheMaintenance = 1, queueIndex = 0, lease = 1, owner = 1;
    IDisposable? candidate = null;
    Gate connectGate = new(), connectionGate = new();
    List<int> queue = new() { 0, 1, 2 }; PlaybackOrder? playbackOrder = new(3, 0, false, false);
    string? renderedState; bool realizedEnabled; int saves;
    T C<T>(string name) where T : new() => new T();
    void SetConnecting(bool value) { }
    void RefreshRatingViews() { }
    void ScheduleCache(bool allowCurrentHandoff = true) { }
    void SavePlayback() { saves++; }
    void RenderQueue() {
        ${state}
        if (renderedState == state) return;
        renderedState = state;
        var order = playbackOrder;
        realizedEnabled = ${enabled};
    }
    void CompleteReconnect() {
        ${cleanup}
    }
    Action CaptureShuffle() {
        var capturedQueue = queue; var order = playbackOrder;
        return () => {
            ${click}
        };
    }
    static void Require(bool ok, string message) { if (!ok) throw new Exception(message); }
    public static void Run() {
        var h = new Harness();
        h.RenderQueue(); Require(h.realizedEnabled, "initial queue should allow shuffle");
        var stale = h.CaptureShuffle();
        h.accountChangePending = true;
        h.RenderQueue();
        Require(!h.realizedEnabled, "cached heading stayed enabled when account gate closed");
        stale(); Require(h.saves == 0, "stale enabled control bypassed account gate");
        h.CompleteReconnect();
        Require(h.realizedEnabled, "reconnect completion left realized shuffle disabled");
        h.RenderQueue(); Require(h.realizedEnabled, "cached rerender lost shuffle availability");
        stale(); Require(h.saves == 1, "same-account queue action did not recover");
        h.connectionGate.Current = false; stale();
        Require(h.saves == 1, "stale control bypassed connection lease");
        Console.WriteLine("Reconnect shuffle availability: PASS");
    }
}`);
    const result = spawnSync('dotnet', ['run', '--project', join(dir, 'Checks.csproj'), '-c', 'Release'], { encoding: 'utf8', timeout: 120000 });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.match(result.stdout, /Reconnect shuffle availability: PASS/);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('Played uses a transparent left-chevron native Expander disclosure with right-aligned count', () => {
  const played = method('Expander CreatePlayedExpander()');
  assert.match(played, /PlayedDisclosureStyle/);
  assert.match(played, /header.Children.Add\(Text\("Played", 14\)\)/);
  assert.match(played, /HorizontalAlignment = HorizontalAlignment.Right/);
  assert.match(played, /MaxHeight = 240/);
  const style = shell.slice(shell.indexOf('<Style x:Key="PlayedDisclosureStyle"'), shell.indexOf('</Style>', shell.indexOf('<Style x:Key="PlayedDisclosureStyle"')));
  assert.match(style, /TargetType="Expander"/);
  assert.match(style, /Background" Value="Transparent"/);
  assert.match(style, /BorderThickness="0,0,0,1"/);
  assert.match(style, /x:Name="ExpanderHeader"/);
  assert.match(style, /IsExpanded, Mode=TwoWay/);
  assert.match(style, /x:Name="PlayedChevron"/);
  assert.match(style, /MinHeight="54"/);
  assert.match(style, /x:Name="ExpandDown"/);
  assert.match(style, /x:Name="CollapseUp"/);
});

test('footer removes volume, keeps Play centered with Repeat right of Next, and aligns larger Queue in transport row', () => {
  assert.doesNotMatch(shell, /VolumeSlider|E767|ShuffleButton/);

  assert.match(shell, /x:Name="QueueButton"[^>]*MinHeight="48"/);
  assert.match(shell, /Glyph="&#xE8FD;" FontSize="26"/);
  assert.match(shell, /Text="Queue" FontSize="18"/);
  const transport = shell.split('x:Name="PlayerTransportRow"')[1]?.split('x:Name="PlayerSeekRow"')[0] ?? '';
  assert.match(transport, /<Grid Grid.Column="2"[^>]*>\s*<Grid\.ColumnDefinitions>\s*<ColumnDefinition Width="Auto"\/><ColumnDefinition Width="\*"\/>[\s\S]*?<\/StackPanel>\s*<Button Grid.Column="1" x:Name="QueueButton"[^>]*HorizontalAlignment="Right"[^>]*VerticalAlignment="Center"/);
  assert.match(shell, /<Grid Grid.Row="1" x:Name="PlayerSeekRow"[\s\S]*?<Grid Grid.Column="1"[^>]*ColumnSpacing="8"[^>]*>[\s\S]*?x:Name="SeekSlider"/);
  assert.match(shell, /Grid.Column="1" x:Name="PlayButton"/);
  assert.match(shell, /Grid.Column="2"[^>]*[\s\S]*x:Name="NextButton"[\s\S]*x:Name="RepeatButton"/);
  assert.doesNotMatch(read('MainWindow.PlayerControls.cs'), /VolumeSlider/);
});

test('native acceptance checks the real shuffle action and materialized cold restoration without deleted controls', () => {
  const driver = readFileSync(new URL('native/NativeAcceptance.cs', import.meta.url), 'utf8');
  assert.doesNotMatch(driver, /ShuffleButton|VolumeSlider|Shuffle: true/);
  assert.match(driver, /shufflePreservesSource/);
  assert.match(driver, /shuffleChangesOnlyPending/);
  assert.match(driver, /remainingOrderRestoration/);
  assert.match(driver, /playedDisclosureExpansion/);
  assert.match(driver, /pendingOccurrenceRows/);
  assert.match(driver, /pendingRows\.Select\(x => x\.PendingOffset\)/);
});
