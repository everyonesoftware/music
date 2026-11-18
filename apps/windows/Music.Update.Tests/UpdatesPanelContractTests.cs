using System.Xml.Linq;
using Xunit;

namespace Music.Update.Tests;

public sealed class UpdatesPanelContractTests
{
    [Fact]
    public void Matched_card_places_version_and_check_above_a_compact_status_card()
    {
        var document = XDocument.Load(Path.Combine(AppContext.BaseDirectory, "Shell.xaml"));
        XNamespace x = "http://schemas.microsoft.com/winfx/2006/xaml";
        var named = document.Descendants().Where(e => e.Attribute(x + "Name") is not null).ToDictionary(e => e.Attribute(x + "Name")!.Value);
        var version = named["VersionText"];
        var header = version.Parent!;
        Assert.Equal("Grid", header.Name.LocalName);
        Assert.Equal(header, named["CheckUpdatesButton"].Parent);
        Assert.Equal("1", named["CheckUpdatesButton"].Attribute("Grid.Column")?.Value);
        Assert.Equal("Right", named["CheckUpdatesButton"].Attribute("HorizontalAlignment")?.Value);
        var card = header.ElementsAfterSelf().Single();
        Assert.Equal("Border", card.Name.LocalName);
        Assert.Equal("24,22", card.Attribute("Padding")?.Value);
        Assert.Null(card.Attribute("MinHeight"));
        Assert.Null(card.Attribute("Height"));
        Assert.Contains(named["UpdateStatus"], card.Descendants());
        Assert.Contains(named["UpdateTitle"], card.Descendants());
        Assert.Equal("22", named["UpdateTitle"].Attribute("FontSize")?.Value);
        Assert.DoesNotContain(named["CheckUpdatesButton"], card.Descendants());
        // One collapsed actions container avoids reserving a StackPanel spacing slot when current.
        Assert.Equal("Collapsed", named["UpdateActions"].Attribute("Visibility")?.Value);
        var section = header.Parent!;
        Assert.DoesNotContain(section.Descendants(), e => (e.Attribute("Text")?.Value ?? "").Contains("preview", StringComparison.OrdinalIgnoreCase));
        Assert.DoesNotContain(section.Descendants(), e => (e.Attribute("Text")?.Value ?? "").Contains("Automatic updates"));
    }

    [Fact]
    public void Runtime_xaml_exposes_explicit_actions_progress_and_accessible_status()
    {
        var document = XDocument.Load(Path.Combine(AppContext.BaseDirectory, "Shell.xaml"));
        XNamespace x = "http://schemas.microsoft.com/winfx/2006/xaml";
        var named = document.Descendants().Where(e => e.Attribute(x + "Name") is not null).ToDictionary(e => e.Attribute(x + "Name")!.Value);
        foreach (string name in new[] { "CheckUpdatesButton", "DownloadUpdateButton", "RestartUpdateButton" })
        {
            Assert.True(named.ContainsKey(name), $"Missing explicit action {name}");
            Assert.Equal("Button", named[name].Name.LocalName);
        }
        Assert.Equal("ProgressBar", named["UpdateProgress"].Name.LocalName);
        Assert.Equal("Polite", named["UpdateStatus"].Attribute("AutomationProperties.LiveSetting")?.Value);
        Assert.Equal("Collapsed", named["DownloadUpdateButton"].Attribute("Visibility")?.Value);
        Assert.Equal("Collapsed", named["RestartUpdateButton"].Attribute("Visibility")?.Value);
    }
}
