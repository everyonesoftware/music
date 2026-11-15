namespace Music.App;

// Session migration is allowed only for the same normalized endpoint and account.
public static class ConnectionIdentity
{
    public static bool SameAccount(string previousServer, string previousUsername, string server, string username)
    {
        if (!string.Equals(previousUsername, username, StringComparison.Ordinal) ||
            !Uri.TryCreate(previousServer, UriKind.Absolute, out var previous) ||
            !Uri.TryCreate(server, UriKind.Absolute, out var next)) return false;
        return previous.Scheme == "https" && next.Scheme == "https" &&
            previous.IdnHost.Equals(next.IdnHost, StringComparison.OrdinalIgnoreCase) &&
            previous.Port == next.Port &&
            string.Equals(previous.AbsolutePath.TrimEnd('/'), next.AbsolutePath.TrimEnd('/'), StringComparison.Ordinal) &&
            previous.UserInfo.Length == 0 && next.UserInfo.Length == 0 &&
            previous.Query.Length == 0 && next.Query.Length == 0 &&
            previous.Fragment.Length == 0 && next.Fragment.Length == 0;
    }
}
