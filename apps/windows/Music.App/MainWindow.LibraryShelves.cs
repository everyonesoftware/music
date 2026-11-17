using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Music.Core;
using Windows.System;

namespace Music.App;

public sealed partial class MainWindow
{
    private sealed record ShelfArrows(Button Previous, Button Next);

    private static Grid CreateLibraryShelfHeader(HyperlinkButton heading, string title)
    {
        Button Arrow(string name, string glyph)
        {
            var button = new Button
            {
                Content = new FontIcon { Glyph = glyph, FontSize = 12 },
                Width = 32, Height = 32, Padding = new Thickness(0), CornerRadius = new CornerRadius(5),
                Foreground = Sage, IsEnabled = false
            };
            AutomationProperties.SetName(button, name);
            ToolTipService.SetToolTip(button, name);
            return button;
        }
        var previous = Arrow("Previous " + title.ToLowerInvariant(), "\uE76B");
        var next = Arrow("Next " + title.ToLowerInvariant(), "\uE76C");
        var actions = new StackPanel
        {
            Orientation = Orientation.Horizontal, Spacing = 6, HorizontalAlignment = HorizontalAlignment.Right
        };
        actions.Children.Add(previous); actions.Children.Add(next);
        var header = new Grid { ColumnSpacing = 16, Tag = new ShelfArrows(previous, next) };
        header.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        header.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        Grid.SetColumn(actions, 1);
        header.Children.Add(heading); header.Children.Add(actions);
        return header;
    }

    private void BindLibraryShelf(StackPanel section, ListViewBase view)
    {
        if (section.Children.OfType<Grid>().FirstOrDefault()?.Tag is not ShelfArrows arrows) return;
        Action? detach = null;
        void Detach()
        {
            detach?.Invoke(); detach = null;
            arrows.Previous.IsEnabled = arrows.Next.IsEnabled = false;
        }
        void Attach(object sender, RoutedEventArgs args)
        {
            Detach();
            view.ApplyTemplate();
            var scroller = ShelfScrollViewer(view);
            if (scroller is null || scroller.Content is not UIElement wheelSurface) return;
            // Intercept on the content BELOW the internal ScrollViewer, not on ListView:
            // by the time a wheel bubbles to ListView, ScrollViewer can already have
            // converted vertical input to horizontal. handledEventsToo also catches
            // input marked handled by a card/container. Register exactly once.
            scroller.HorizontalSnapPointsType = SnapPointsType.None;
            var main = C<ScrollViewer>("MainScroll");
            double? horizontalTarget = null, verticalTarget = null;
            void UpdateArrows()
            {
                var state = LibraryShelfScrollPolicy.Boundaries(scroller.HorizontalOffset, scroller.ScrollableWidth);
                if (arrows.Previous.IsEnabled != state.Previous) arrows.Previous.IsEnabled = state.Previous;
                if (arrows.Next.IsEnabled != state.Next) arrows.Next.IsEnabled = state.Next;
            }
            void HorizontalViewChanged(object? _, ScrollViewerViewChangedEventArgs e)
            {
                if (!e.IsIntermediate) horizontalTarget = null;
                UpdateArrows();
            }
            void VerticalViewChanged(object? _, ScrollViewerViewChangedEventArgs e)
            {
                if (!e.IsIntermediate) verticalTarget = null;
            }
            void SizeChanged(object _, SizeChangedEventArgs e) => UpdateArrows();
            void LayoutUpdated(object? _, object e) => UpdateArrows();
            void Move(int direction)
            {
                horizontalTarget = LibraryShelfScrollPolicy.Clamp(
                    (horizontalTarget ?? scroller.HorizontalOffset) + direction * Math.Max(176, scroller.ViewportWidth * .8), scroller.ScrollableWidth);
                if (!scroller.ChangeView(horizontalTarget, null, null, true)) horizontalTarget = null;
            }
            void Previous(object _, RoutedEventArgs e) => Move(-1);
            void Next(object _, RoutedEventArgs e) => Move(1);
            PointerEventHandler wheelHandler = (_, e) =>
            {
                var properties = e.GetCurrentPoint(wheelSurface).Properties;
                var motion = LibraryShelfScrollPolicy.Wheel(properties.MouseWheelDelta,
                    (e.KeyModifiers & VirtualKeyModifiers.Shift) != 0, properties.IsHorizontalMouseWheel);
                // Always consume this wheel, including at boundaries: neither the row
                // nor an ancestor may convert or apply this same event a second time.
                e.Handled = true;
                if (motion.Horizontal)
                {
                    horizontalTarget = LibraryShelfScrollPolicy.Clamp(
                        (horizontalTarget ?? scroller.HorizontalOffset) + motion.Delta, scroller.ScrollableWidth);
                    if (!scroller.ChangeView(horizontalTarget, null, null, true)) horizontalTarget = null;
                }
                else
                {
                    verticalTarget = LibraryShelfScrollPolicy.Clamp(
                        (verticalTarget ?? main.VerticalOffset) + motion.Delta, main.ScrollableHeight);
                    if (!main.ChangeView(null, verticalTarget, null, true)) verticalTarget = null;
                }
            };
            wheelSurface.AddHandler(UIElement.PointerWheelChangedEvent, wheelHandler, true);
            scroller.ViewChanged += HorizontalViewChanged;
            main.ViewChanged += VerticalViewChanged;
            scroller.SizeChanged += SizeChanged;
            scroller.LayoutUpdated += LayoutUpdated; // Extent changes need not resize the viewport.
            arrows.Previous.Click += Previous;
            arrows.Next.Click += Next;
            detach = () =>
            {
                wheelSurface.RemoveHandler(UIElement.PointerWheelChangedEvent, wheelHandler);
                scroller.ViewChanged -= HorizontalViewChanged;
                main.ViewChanged -= VerticalViewChanged;
                scroller.SizeChanged -= SizeChanged;
                scroller.LayoutUpdated -= LayoutUpdated;
                arrows.Previous.Click -= Previous;
                arrows.Next.Click -= Next;
            };
            UpdateArrows();
        }
        view.Loaded += Attach;
        view.Unloaded += (_, _) => Detach();
        // Leave native keyboard focus/bring-into-view and touch/precision-touchpad
        // DirectManipulation intact. Precision gestures are not wheel events.
    }

    private static ScrollViewer? ShelfScrollViewer(DependencyObject parent)
    {
        for (int i = 0; i < VisualTreeHelper.GetChildrenCount(parent); i++)
        {
            var child = VisualTreeHelper.GetChild(parent, i);
            if (child is ScrollViewer scroller) return scroller;
            if (ShelfScrollViewer(child) is { } descendant) return descendant;
        }
        return null;
    }
}
