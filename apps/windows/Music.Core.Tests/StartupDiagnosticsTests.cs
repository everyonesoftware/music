using Xunit;
using Music.App;

public sealed class StartupDiagnosticsTests
{
    [Fact]
    public void DisabledDiagnosticsDoNotWrite()
    {
        StartupDiagnostics.Write(new InvalidOperationException("fixture startup failure"), null);
    }

    [Fact]
    public void OptInDiagnosticsRetainExceptionTypeAndFailure()
    {
        var directory = Path.Combine(Path.GetTempPath(), "music-startup-" + Guid.NewGuid().ToString("N"));
        try
        {
            StartupDiagnostics.Write(new InvalidOperationException("fixture startup failure"), directory);
            var text = File.ReadAllText(Path.Combine(directory, "startup-failure.txt"));
            Assert.Contains("InvalidOperationException", text);
            Assert.Contains("fixture startup failure", text);
        }
        finally { if (Directory.Exists(directory)) Directory.Delete(directory, true); }
    }

    [Fact]
    public void DiagnosticIoFailureCannotMaskTheOriginalFailure()
    {
        var path = Path.GetTempFileName();
        try
        {
            StartupDiagnostics.Write(new InvalidOperationException("original failure"), path);
            Assert.True(File.Exists(path));
        }
        finally { File.Delete(path); }
    }
}
