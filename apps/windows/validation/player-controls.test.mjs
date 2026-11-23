import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';

const app = new URL('../Music.App/', import.meta.url);
const shell = readFileSync(new URL('Shell.xaml', app), 'utf8').replaceAll('\r\n', '\n');
const main = readFileSync(new URL('MainWindow.cs', app), 'utf8').replaceAll('\r\n', '\n');

// Rendering-source contracts, not native WinUI screenshot acceptance.
test('footer transport uses filled vector triangles and retains them across play/pause updates', () => {
  for (const name of ['PreviousButton', 'NextButton']) {
    const button = shell.match(new RegExp(`<Button\\b[^>]*\\sx:Name="${name}"[^>]*>[\\s\\S]*?</Button>`))?.[0];
    assert.ok(button, `${name} must have vector content`);
    assert.match(button, /<PathIcon\b/);
    assert.doesNotMatch(button, /(?:Fill|Foreground)="/);
    assert.match(button, /Data="[^"]*Z/);
    assert.doesNotMatch(button, /Content="&#x/);
  }
  assert.match(shell, /<PathIcon x:Name="PlayerPlayIcon"[^>]*Data="[^"]*Z/);
  assert.match(shell, /<PathIcon x:Name="PlayerPauseIcon"[^>]*Visibility="Collapsed"/);
  for (const icon of shell.matchAll(/<PathIcon\b[^>]*\/>/g)) assert.doesNotMatch(icon[0], /Foreground="/);
  assert.match(main, /C<PathIcon>\("PlayerPlayIcon"\)\.Visibility = playing \? Visibility.Collapsed : Visibility.Visible/);
  assert.match(main, /C<PathIcon>\("PlayerPauseIcon"\)\.Visibility = playing \? Visibility.Visible : Visibility.Collapsed/);
  assert.doesNotMatch(main, /C<Button>\("PlayButton"\)\.Content =/);
});

test('seek retains an Android-style rounded bar without replacing native slider behavior', () => {
  assert.match(shell, /<ControlTemplate x:Key="PlayerSliderThumbTemplate" TargetType="Thumb">/);
  const template = shell.match(/<ControlTemplate x:Key="PlayerSliderThumbTemplate"[\s\S]*?<\/ControlTemplate>/)?.[0] ?? '';
  assert.match(template, /Background="Transparent"/);
  assert.match(template, /Width="4" Height="28" CornerRadius="2" Background="\{TemplateBinding Background\}"/);
  assert.doesNotMatch(template, /Ellipse|ScaleTransform|CompositeTransform/);
  const path = new URL('MainWindow.PlayerControls.cs', app);
  const controls = existsSync(path) ? readFileSync(path, 'utf8') : '';
  assert.match(main, /InitializePlayerControls\(\)/);
  assert.ok(controls.includes('"SeekSlider"'));
  assert.doesNotMatch(controls, /VolumeSlider/);
  assert.match(controls, /slider.Loaded \+=/);
  assert.match(controls, /slider.ApplyTemplate\(\)/);
  assert.match(controls, /Name: "HorizontalThumb"/);
  assert.match(controls, /thumb.Template = .*root.Resources\["PlayerSliderThumbTemplate"\]/);
  assert.match(controls, /thumb.Width = 24/);
  assert.match(controls, /thumb.Height = 32/);
  assert.doesNotMatch(controls, /slider.Template\s*=|PointerPressed|PointerMoved|KeyDown|ValueChanged/);
});
