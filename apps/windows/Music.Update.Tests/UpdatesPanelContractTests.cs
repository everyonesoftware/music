using System.Xml.Linq;
using Xunit;

namespace Music.Update.Tests;

public sealed class UpdatesPanelContractTests
{
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
