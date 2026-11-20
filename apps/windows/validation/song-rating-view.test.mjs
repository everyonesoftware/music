import test from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';

const path = new URL('../Music.App/SongRatingView.cs', import.meta.url);
function viewSource() {
  assert.ok(existsSync(path), 'Music.App must provide the reusable native rating view');
  return readFileSync(path, 'utf8').replaceAll('\r\n', '\n');
}

// Source contracts. Native focus, gesture routing and flyout behavior need Windows acceptance.
test('inline stars are a controlled view with a native clear command and read-only state', () => {
  const view = viewSource();
  assert.match(view, /static FrameworkElement CreateInline\(Action<int> pick, out Action<int, bool> refresh\)/);
  assert.match(view, /const int StarCount = 5;/);
  assert.match(view, /refresh = stars\.Refresh;/);
  assert.match(view, /button\.Click \+= .*Request\(value\)/);
  assert.match(view, /if \(enabled\) pick\(value\);/);
  assert.match(view, /button\.IsEnabled = enabled;/);
  assert.match(view, /Text = "Clear rating"/);
  assert.match(view, /clear\.Click \+= .*Request\(0\)/);
  assert.match(view, /button\.ContextFlyout = enabled \? clearMenu : null/);
});

test('star navigation uses one tab stop and never commits while moving focus or previewing', () => {
  const view = viewSource();
  assert.match(view, /IsTabStop = enabled && .*index/);
  const keys = view.match(/private void MoveFocus\([\s\S]*?\n        }/)?.[0];
  assert.ok(keys, 'star keyboard handler must move focus inside the rating group');
  for (const key of ['Left', 'Right', 'Up', 'Down', 'Home', 'End']) assert.ok(keys.includes(`VirtualKey.${key}`));
  assert.match(keys, /args\.Handled = true/);
  assert.match(keys, /Focus\(FocusState.Keyboard\)/);
  assert.doesNotMatch(keys, /Request\(|\bpick\(/);
  assert.match(view, /button\.PointerEntered \+= .*Paint\(value\)/);
  assert.match(view, /Panel\.PointerExited \+= .*Paint\(\)/);
  assert.match(view, /int visible = enabled && preview > 0 \? preview : rating;/);
  assert.match(view, /icons\[i\]\.Glyph = selected \? Filled : Outline;/);
  assert.match(view, /\$"\{subject\}: \{RatingText\(i \+ 1\)\}\{current\}"/);
});

test('nested rating activation handles release and tap without taking over pointer presses', () => {
  const view = viewSource();
  assert.match(view, /IsolateActivation\(Panel\)/);
  assert.match(view, /IsolatedTriggers\.GetValue\(button,[\s\S]*?IsolateActivation\(trigger\)/);
  for (const [event, handler] of [
    ['PointerReleased', 'Pointer'], ['Tapped', 'Tapped'], ['DoubleTapped', 'DoubleTapped'],
  ]) {
    assert.match(view, new RegExp(`AddHandler\\(UIElement\\.${event}Event, new ${handler}EventHandler\\(.*Handled = true\\), true\\)`));
  }
  assert.doesNotMatch(view, /PointerPressed|CapturePointer|ManipulationMode/);
});

test('compact trigger conveys confirmed rating without relying on color or an unlabeled icon', () => {
  const view = viewSource();
  const trigger = view.match(/static void UpdateTrigger\(Button button, Song song, int rating, bool enabled\)[\s\S]*?\n    }/)?.[0];
  assert.ok(trigger, 'provide the compact player/queue trigger updater');
  assert.match(trigger, /Star\(rating > 0,/);
  assert.match(trigger, /if \(rating > 0\)[\s\S]*?Text = rating\.ToString\(/);
  assert.match(trigger, /AutomationProperties\.SetName\(button, .*song\.Title.*RatingText\(rating\)/);
  assert.match(trigger, /ToolTipService\.SetToolTip\(button,/);
  assert.match(trigger, /button\.IsEnabled = enabled;/);
  assert.match(view, /"Unrated"/);
  assert.match(view, /\\uE734/);
  assert.match(view, /\\uE735/);
});

test('picker names the song, starts at the committed value, and closes before requesting a change', () => {
  const view = viewSource();
  const picker = view.match(/static Flyout CreatePicker\(Song song, int rating, Action<int> pick\)[\s\S]*?\n    }/)?.[0];
  assert.ok(picker, 'provide the anchored native picker factory');
  assert.match(picker, /Text = song\.Title/);
  assert.match(picker, /Text = song\.Artist/);
  assert.match(picker, /Text = RatingText\(rating\)/);
  assert.match(picker, /stars\.Refresh\(rating, true\)/);
  assert.match(picker, /flyout\.Opened \+= .*FocusCurrent\(\)/);
  assert.match(picker, /Content = "Clear rating"/);
  assert.match(picker, /clear\.Click \+= .*Choose\(0\)/);
  assert.match(picker, /flyout\.Hide\(\);\s*pick\(value\);/);
  assert.doesNotMatch(view, /NavidromeClient|HttpClient|MainWindow|async void/);
});
