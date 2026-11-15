namespace Music.App;

// Opt-in evidence for disposable CI launches only; normal use writes no exception log.
internal static class StartupDiagnostics
{
    internal static void Write(Exception exception, string? directory)
    {
        if (string.IsNullOrWhiteSpace(directory)) return;
        try
        {
            Directory.CreateDirectory(directory);
            File.AppendAllText(Path.Combine(directory, "startup-failure.txt"), $"{DateTime.UtcNow:o}\n{exception}\n");
        }
        catch (Exception) { /* Diagnostic failures must not mask the original exception. */ }
    }
}
