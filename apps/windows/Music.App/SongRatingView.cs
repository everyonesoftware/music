using System.Globalization;
using System.Runtime.CompilerServices;
using Microsoft.UI;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Automation.Peers;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Music.Core;
using Windows.System;

namespace Music.App;

/// <summary>Dispatcher-owned rating presentation; the caller owns song identity and writes.</summary>
internal static class SongRatingView
{
    private const int StarCount = 5;
    private const string Outline = "\uE734";
    private const string Filled = "\uE735";
    private static readonly ConditionalWeakTable<Button, object> IsolatedTriggers = new();

    /// <summary>Starts disabled. Refresh with the confirmed rating (0–5) and write eligibility.</summary>
    public static FrameworkElement CreateInline(Action<int> pick, out Action<int, bool> refresh)
    {
        var stars = new Stars(pick, "Song rating", inline: true);
        refresh = stars.Refresh;
        stars.Refresh(0, false);
        return stars.Panel;
    }

    /// <summary>Updates a player or Queue button without replacing its activation handler.</summary>
    public static void UpdateTrigger(Button button, Song song, int rating, bool enabled)
    {
        rating = Clamp(rating);
        IsolatedTriggers.GetValue(button, static trigger =>
        {
            IsolateActivation(trigger);
            return new object();
        });
        var content = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 4, IsHitTestVisible = false };
        content.Children.Add(Star(rating > 0, 16));
        if (rating > 0)
            content.Children.Add(new TextBlock { Text = rating.ToString(CultureInfo.CurrentCulture), VerticalAlignment = VerticalAlignment.Center });
        button.Content = content;
        button.Foreground = RatingBrush(rating > 0);
        AutomationProperties.SetName(button, $"Rate {song.Title}, {RatingText(rating)}");
        ToolTipService.SetToolTip(button, $"Rate song: {RatingText(rating)}");
        button.IsEnabled = enabled;
    }

    /// <summary>Create for the current song and rating each time the anchor is activated.</summary>
    public static Flyout CreatePicker(Song song, int rating, Action<int> pick)
    {
        rating = Clamp(rating);
        var flyout = new Flyout { Placement = FlyoutPlacementMode.Top, ShowMode = FlyoutShowMode.Standard };
        var stars = new Stars(Choose, $"Rating for {song.Title}", inline: false);
        stars.Refresh(rating, true);
        var panel = new StackPanel { Spacing = 6, MaxWidth = 300 };
        var heading = new TextBlock { Text = "Rate song", FontSize = 16, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold };
        AutomationProperties.SetHeadingLevel(heading, AutomationHeadingLevel.Level2);
        panel.Children.Add(heading);
        panel.Children.Add(new TextBlock { Text = song.Title, TextWrapping = TextWrapping.Wrap });
        if (!string.IsNullOrWhiteSpace(song.Artist))
            panel.Children.Add(new TextBlock { Text = song.Artist, TextWrapping = TextWrapping.Wrap });
        panel.Children.Add(stars.Panel);
        panel.Children.Add(new TextBlock { Text = RatingText(rating) });
        var clear = new Button { Content = "Clear rating" };
        clear.Click += (_, _) => Choose(0);
        panel.Children.Add(clear);
        flyout.Content = panel;
        flyout.Opened += (_, _) => stars.FocusCurrent();
        return flyout;

        void Choose(int value)
        {
            flyout.Hide();
            pick(value);
        }
    }

    private static int Clamp(int rating) => Math.Clamp(rating, 0, StarCount);
    private static string RatingText(int rating) => rating switch
    {
        0 => "Unrated",
        1 => "1 star out of 5",
        _ => $"{rating} stars out of 5"
    };

    // FontIcon's default symbol font resolves to the platform's Segoe icon font.
    private static FontIcon Star(bool filled, double size) => new()
    {
        Glyph = filled ? Filled : Outline,
        FontSize = size,
        IsHitTestVisible = false
    };

    private static SolidColorBrush RatingBrush(bool filled) => new(filled
        ? ColorHelper.FromArgb(255, 192, 215, 174)
        : ColorHelper.FromArgb(255, 172, 184, 174));

    private static void IsolateActivation(UIElement target)
    {
        // Native Button.Click has already processed the release. Stop list-row
        // activation without taking over presses, pointer capture, or scrolling.
        target.AddHandler(UIElement.PointerReleasedEvent, new PointerEventHandler((_, args) => args.Handled = true), true);
        target.AddHandler(UIElement.TappedEvent, new TappedEventHandler((_, args) => args.Handled = true), true);
        target.AddHandler(UIElement.DoubleTappedEvent, new DoubleTappedEventHandler((_, args) => args.Handled = true), true);
    }

    private sealed class Stars
    {
        private readonly Action<int> pick;
        private readonly string subject;
        private readonly Button[] buttons = new Button[StarCount];
        private readonly FontIcon[] icons = new FontIcon[StarCount];
        private readonly SolidColorBrush filled = RatingBrush(true);
        private readonly SolidColorBrush outline = RatingBrush(false);
        private readonly MenuFlyout? clearMenu;
        private int rating = -1;
        private bool enabled;

        public StackPanel Panel { get; } = new()
        {
            Orientation = Orientation.Horizontal,
            HorizontalAlignment = HorizontalAlignment.Left,
            VerticalAlignment = VerticalAlignment.Center,
            Background = new SolidColorBrush(Colors.Transparent)
        };

        public Stars(Action<int> pick, string subject, bool inline)
        {
            this.pick = pick;
            this.subject = subject;
            AutomationProperties.SetName(Panel, subject);
            Panel.Spacing = inline ? 0 : 4;
            IsolateActivation(Panel);
            if (inline)
            {
                var clear = new MenuFlyoutItem { Text = "Clear rating" };
                clear.Click += (_, _) => Request(0);
                clearMenu = new MenuFlyout();
                clearMenu.Items.Add(clear);
            }
            for (int i = 0; i < StarCount; i++)
            {
                int value = i + 1;
                var icon = Star(false, inline ? 16 : 22);
                var button = new Button
                {
                    Content = icon,
                    Width = inline ? 28 : 44,
                    Height = inline ? 36 : 44,
                    Padding = new Thickness(0),
                    BorderThickness = new Thickness(0),
                    Background = Panel.Background,
                    HorizontalContentAlignment = HorizontalAlignment.Center,
                    VerticalContentAlignment = VerticalAlignment.Center
                };
                buttons[i] = button;
                icons[i] = icon;
                button.Click += (_, _) => Request(value);
                button.GotFocus += (_, _) => SetTabStop(value - 1);
                button.KeyDown += (_, args) => MoveFocus(value - 1, args);
                button.PointerEntered += (_, _) => Paint(value);
                Panel.Children.Add(button);
            }
            Panel.PointerExited += (_, _) => Paint();
        }

        public void Refresh(int value, bool canWrite)
        {
            value = Clamp(value);
            if (rating == value && enabled == canWrite) return;
            rating = value;
            enabled = canWrite;
            var focused = Panel.XamlRoot is { } root ? FocusManager.GetFocusedElement(root) as Button : null;
            int focusedIndex = Array.IndexOf(buttons, focused);
            for (int i = 0; i < buttons.Length; i++)
            {
                var button = buttons[i];
                button.IsEnabled = enabled;
                button.ContextFlyout = enabled ? clearMenu : null;
                string current = i + 1 == rating ? ", current rating" : "";
                AutomationProperties.SetName(button, $"{subject}: {RatingText(i + 1)}{current}");
            }
            SetTabStop(focusedIndex < 0 ? CurrentIndex : focusedIndex);
            Paint();
        }

        public void FocusCurrent()
        {
            SetTabStop(CurrentIndex);
            buttons[CurrentIndex].Focus(FocusState.Programmatic);
        }

        private int CurrentIndex => Math.Max(0, rating - 1);

        private void SetTabStop(int index)
        {
            for (int i = 0; i < buttons.Length; i++) buttons[i].IsTabStop = enabled && i == index;
        }

        private void MoveFocus(int index, KeyRoutedEventArgs args)
        {
            if (!enabled) return;
            int next = args.Key switch
            {
                VirtualKey.Left or VirtualKey.Up => Math.Max(0, index - 1),
                VirtualKey.Right or VirtualKey.Down => Math.Min(StarCount - 1, index + 1),
                VirtualKey.Home => 0,
                VirtualKey.End => StarCount - 1,
                _ => -1
            };
            if (next < 0) return;
            args.Handled = true;
            SetTabStop(next);
            buttons[next].Focus(FocusState.Keyboard);
        }

        private void Paint(int preview = 0)
        {
            int visible = enabled && preview > 0 ? preview : rating;
            for (int i = 0; i < buttons.Length; i++)
            {
                bool selected = i < visible;
                icons[i].Glyph = selected ? Filled : Outline;
                buttons[i].Foreground = selected ? filled : outline;
            }
        }

        private void Request(int value)
        {
            Paint();
            if (enabled) pick(value);
        }
    }
}
