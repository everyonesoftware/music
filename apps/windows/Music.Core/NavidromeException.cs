namespace Music.Core;

/// <summary>Sanitized failure. Deliberately retains no transport exception or authenticated URI.</summary>
public sealed class NavidromeException(string message) : IOException(message);
