import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';

test('real native Playing callback retains evidence across blocked dispatch and rejects stale visits/sources', () => {
  const main = readFileSync(new URL('../Music.App/MainWindow.cs', import.meta.url), 'utf8').replaceAll('\r\n', '\n');
  const start = main.indexOf('            player.Source = item;');
  const callback = main.slice(start, main.indexOf('            if (playIntended) player.Play();', start));
  const dir = mkdtempSync(join(process.env.TMPDIR || '/tmp', 'music-playing-'));
  try {
    const order = fileURLToPath(new URL('../Music.Core/PlaybackOrder.cs', import.meta.url));
    writeFileSync(join(dir, 'Checks.csproj'), `<Project Sdk="Microsoft.NET.Sdk"><PropertyGroup><OutputType>Exe</OutputType><TargetFramework>net10.0</TargetFramework><ImplicitUsings>enable</ImplicitUsings><Nullable>enable</Nullable></PropertyGroup><ItemGroup><Compile Include="${order}"/></ItemGroup></Project>`);
    writeFileSync(join(dir, 'Program.cs'), `
using Music.Core;
await Harness.Run();
enum MediaPlaybackState { Paused, Playing }
class MediaPlaybackItem { }
class Session { public MediaPlaybackState PlaybackState; public event Action<Session, object?>? PlaybackStateChanged; public void Playing() { PlaybackState = MediaPlaybackState.Playing; PlaybackStateChanged?.Invoke(this, null); } }
class NativePlayer { public object? Source; public Session PlaybackSession = new(); }
class Dispatch { public Queue<Action> Pending = new(); public bool TryEnqueue(Action action) { Pending.Enqueue(action); return true; } public void Drain() { while (Pending.TryDequeue(out var action)) action(); } }
class Harness {
    NativePlayer player = new(); Dispatch DispatcherQueue = new();
    PlaybackOrder? playbackOrder = new(3, 0, false, false);
    Action<Session, object?>? startedHandler;
    long playbackVersion; bool closed = false; int uiUpdates;
    void RenderQueue() { uiUpdates++; } void UpdateTransport() { uiUpdates++; }
    Action<Session, object?> Bind() {
        long version = ++playbackVersion;
        var item = new MediaPlaybackItem();
        if (startedHandler != null) player.PlaybackSession.PlaybackStateChanged -= startedHandler;
${callback}
        return startedHandler!;
    }
    static void Require(bool ok, string message) { if (!ok) throw new Exception(message); }
    public static async Task Run() {
        var h = new Harness(); var old = h.Bind();
        await Task.Run(() => h.player.PlaybackSession.Playing());
        Require(h.uiUpdates == 0, "native callback mutated UI");
        h.playbackOrder!.MoveNext(); h.Bind(); // Step(1) while dispatcher is held.
        h.DispatcherQueue.Drain();
        Require(h.playbackOrder.Played.Select(v => v.Index).SequenceEqual(new[] { 0 }), "Playing evidence was lost before departure");
        await Task.Run(() => old(h.player.PlaybackSession, null)); // Late callback from replaced source.
        h.playbackOrder.MoveNext(); h.Bind(); h.DispatcherQueue.Drain();
        Require(h.playbackOrder.Played.Count == 1, "stale start attributed to replacement");
        await Task.Run(() => h.player.PlaybackSession.Playing());
        h.player.PlaybackSession.PlaybackState = MediaPlaybackState.Paused;
        h.Bind(); // same-visit handoff preserves start
        h.playbackOrder.Finish(); h.DispatcherQueue.Drain();
        Require(h.playbackOrder.Played.Select(v => v.Index).SequenceEqual(new[] { 0, 2 }), "handoff/end lost actual start");
        old = h.Bind(); h.playbackOrder = new(3, 0, false, false); h.Bind();
        await Task.Run(() => old(h.player.PlaybackSession, null));
        h.playbackOrder.MoveNext(); h.DispatcherQueue.Drain();
        Require(h.playbackOrder.Played.Count == 0, "old queue marked new queue");
        Console.WriteLine("Playing callback ordering: PASS");
    }
}`);
    const result = spawnSync('dotnet', ['run', '--project', join(dir, 'Checks.csproj'), '-c', 'Release'], { encoding: 'utf8', timeout: 120000 });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.match(result.stdout, /Playing callback ordering: PASS/);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});
