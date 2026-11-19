namespace Music.Core;

public sealed record WindowsChangelog(string BaseRevision, string HeadRevision,
    IReadOnlyList<string> Improvements, IReadOnlyList<string> Fixes, IReadOnlyList<WindowsChangeCommit> Commits);

public sealed record WindowsChangeCommit(string Sha, string Subject);
