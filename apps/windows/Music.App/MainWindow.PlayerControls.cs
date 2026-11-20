using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Media;

namespace Music.App;

public sealed partial class MainWindow
{
    private void InitializePlayerControls()
    {
        var slider = C<Slider>("SeekSlider");
        slider.Loaded += (_, _) =>
        {
            slider.ApplyTemplate();
            ApplyPlayerSliderThumb(slider);
        };
    }

    private void ApplyPlayerSliderThumb(DependencyObject element)
    {
        if (element is Thumb { Name: "HorizontalThumb" } thumb)
        {
            // Keep the SDK Slider template and its drag, keyboard, focus and color
            // states. Only replace the circular thumb artwork, not its hit target.
            thumb.Width = 24;
            thumb.Height = 32;
            thumb.Template = (ControlTemplate)root.Resources["PlayerSliderThumbTemplate"];
            return;
        }
        for (int index = 0; index < VisualTreeHelper.GetChildrenCount(element); index++)
            ApplyPlayerSliderThumb(VisualTreeHelper.GetChild(element, index));
    }
}
