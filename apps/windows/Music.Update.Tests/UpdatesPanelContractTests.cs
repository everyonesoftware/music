using System.Xml.Linq;
using Xunit;

namespace Music.Update.Tests;

public sealed class UpdatesPanelContractTests
{
    private static Dictionary<string, XElement> ReadNamedControls() =>
        XDocument.Load(Path.Combine(AppContext.BaseDirectory, "Shell.xaml")).Descendants()
            .Where(e => e.Attribute(XName.Get("Name", "http://schemas.microsoft.com/winfx/2006/xaml")) is not null)
            .ToDictionary(e => e.Attribute(XName.Get("Name", "http://schemas.microsoft.com/winfx/2006/xaml"))!.Value);

    [Fact]
    public void Shared_host_preserves_the_stretched_vertical_settings_layout()
    {
        var named = ReadNamedControls();
        var host = named["UpdatesPanelHost"];
        var settings = named["SettingsPanel"];
        var scroll = named["MainScroll"];
        Assert.Equal("ContentControl", host.Name.LocalName);
        Assert.Equal("Stretch", host.Attribute("HorizontalContentAlignment")?.Value);
        Assert.Empty(host.Elements());
        Assert.Equal(settings, host.Parent!.Parent);
        Assert.Equal("720", settings.Attribute("MaxWidth")?.Value);
        Assert.Equal("Stretch", settings.Attribute("HorizontalAlignment")?.Value);
        Assert.Contains(scroll, settings.Ancestors());
        Assert.Equal("Disabled", scroll.Attribute("HorizontalScrollMode")?.Value);
        Assert.Equal("Disabled", scroll.Attribute("HorizontalScrollBarVisibility")?.Value);
        Assert.Equal("Auto", scroll.Attribute("VerticalScrollBarVisibility")?.Value);
        Assert.All(new[] { "VersionText", "UpdateTitle", "UpdateStatus", "UpdateOfferedVersion", "UpdateProgress",
            "CheckUpdatesButton", "DownloadUpdateButton", "RestartUpdateButton", "UpdateActions" }, name => Assert.DoesNotContain(name, named.Keys));
    }

    [Fact]
    public void Local_changelog_starts_closed_and_preserves_accessible_retry_content()
    {
        var named = ReadNamedControls();
        var notes = named["UpdateChangelog"];
        var parkingHost = named["ChangelogParkingHost"];
        Assert.Equal("ContentControl", parkingHost.Name.LocalName);
        Assert.Equal("Collapsed", parkingHost.Attribute("Visibility")?.Value);
        Assert.Equal(notes, Assert.Single(parkingHost.Elements()));
        Assert.Equal("Expander", notes.Name.LocalName);
        Assert.Equal("False", notes.Attribute("IsExpanded")?.Value);
        Assert.Equal("What’s new", notes.Attribute("Header")?.Value);
        Assert.Contains(named["ChangelogContent"], notes.Descendants());
        Assert.Contains(named["RetryChangelogButton"], notes.Descendants());
        Assert.Equal("Polite", named["ChangelogStatus"].Attribute("AutomationProperties.LiveSetting")?.Value);
        Assert.Equal("Collapsed", named["RetryChangelogButton"].Attribute("Visibility")?.Value);
    }

    [Fact]
    public void No_duplicate_recheck_button_is_declared()
    {
        var named = ReadNamedControls();
        Assert.DoesNotContain("RecheckUpdateButton", named.Keys);
    }
}
