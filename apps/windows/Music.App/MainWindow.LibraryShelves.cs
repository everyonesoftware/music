using EveryoneSoftware.WindowsCommon;
using Microsoft.UI.Xaml.Controls;

namespace Music.App;

public sealed partial class MainWindow
{
    private static Grid CreateLibraryShelfHeader(HyperlinkButton heading, string title)
    {
        var arrows = new HorizontalShelf(heading, Sage);
        arrows.SetTitle(title);
        arrows.Header.Tag = arrows;
        return arrows.Header;
    }

    private void BindLibraryShelf(StackPanel section, ListViewBase view)
    {
        if (section.Children.OfType<Grid>().FirstOrDefault()?.Tag is HorizontalShelf arrows)
            arrows.Bind(view, () => C<ScrollViewer>("MainScroll"));
    }
}
