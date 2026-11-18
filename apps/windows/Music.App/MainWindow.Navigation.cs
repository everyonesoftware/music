using Microsoft.UI.Input;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;

namespace Music.App;

public sealed partial class MainWindow
{
    private readonly NavigationHistory navigationHistory = new();
    private long navigationRevision;
    private object? detailCollection;
    private bool updatingNavigation;

    private NavigationView CaptureNavigation() => new(page, query, mode, page == "detail" ? detailCollection : null);

    private void InitializeNavigation()
    {
        // Include controls that handle PointerPressed themselves (cards, lists, buttons).
        root.AddHandler(UIElement.PointerPressedEvent, new PointerEventHandler(async (_, args) =>
        {
            if (args.GetCurrentPoint(root).Properties.PointerUpdateKind != PointerUpdateKind.XButton1Pressed) return;
            args.Handled = true;
            if (!CanNavigateBack(args.OriginalSource as DependencyObject)) return;
            await GoBackAsync();
        }), true);
    }

    private bool CanNavigateBack(DependencyObject? source)
    {
        if (closed || accountChangePending || root.XamlRoot is null) return false;
        // Don't dismiss an editor, menu, flyout, or modal through shell navigation.
        if (VisualTreeHelper.GetOpenPopupsForXamlRoot(root.XamlRoot).Count != 0) return false;
        return !IsNavigationEditor(source) &&
            !IsNavigationEditor(FocusManager.GetFocusedElement(root.XamlRoot) as DependencyObject);
    }

    private static bool IsNavigationEditor(DependencyObject? element)
    {
        while (element is not null)
        {
            if (element is TextBox or PasswordBox or RichEditBox or NumberBox or AutoSuggestBox or ContentDialog) return true;
            element = VisualTreeHelper.GetParent(element);
        }
        return false;
    }

    private async Task GoBackAsync()
    {
        if (!navigationHistory.TryBack(out var previous) || previous is null) return;
        if (previous.Page == "detail" && previous.Collection is not null)
        {
            query = previous.Query; mode = previous.Mode;
            await OpenCollectionAsync(previous.Collection, remember: false);
        }
        else await NavigateAsync(previous.Page, remember: false, restore: previous);
    }
}
