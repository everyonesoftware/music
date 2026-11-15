namespace Music.App;
public static class BrowsePolicy
{
    public static string DefaultMode(string page) => page == "songs" ? "list" : "grid";
    public static bool ShowSection(string page, string section, int count) => count > 0 && (page == "library" || page == section);
    public static bool ShowViewAll(string page) => page == "library";
}
