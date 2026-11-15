using System.Text.Json;
using Music.Core;
using Windows.Security.Credentials;

namespace Music.App;

internal sealed class ProfileStore
{
    private const string Resource = "EveryoneSoftware.Music";
    public string DirectoryPath { get; } = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "EveryoneSoftware", "Music");
    private string ProfilePath => Path.Combine(DirectoryPath, "connection.json");
    public ProfileStore() => Directory.CreateDirectory(DirectoryPath);
    public ConnectionProfile? ReadProfile() => File.Exists(ProfilePath) ? JsonSerializer.Deserialize(File.ReadAllText(ProfilePath), PersistenceJsonContext.Default.ConnectionProfile) : null;
    public string ReadPassword(ConnectionProfile profile)
    {
        var credential = new PasswordVault().Retrieve(Resource, profile.Id);
        credential.RetrievePassword();
        return credential.Password;
    }
    // The caller has already pinged and loaded the candidate. New profiles have
    // unique IDs so a failed write never destroys an existing working credential.
    public void Commit(ConnectionProfile profile, string password)
    {
        var previous = ReadProfile();
        var vault = new PasswordVault();
        var credential = new PasswordCredential(Resource, profile.Id, password);
        vault.Add(credential);
        string temp = ProfilePath + ".new";
        try
        {
            File.WriteAllText(temp, JsonSerializer.Serialize(profile, PersistenceJsonContext.Default.ConnectionProfile));
            File.Move(temp, ProfilePath, true);
        }
        catch
        {
            try { vault.Remove(credential); } catch { /* Preserve the original failure. */ }
            try { File.Delete(temp); } catch { }
            throw;
        }
        if (previous is not null && previous.Id != profile.Id)
            try { vault.Remove(vault.Retrieve(Resource, previous.Id)); } catch { /* Orphan cleanup is noncritical. */ }
    }
}
