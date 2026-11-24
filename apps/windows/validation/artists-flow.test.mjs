import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync, mkdtempSync, rmSync, mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
const app = new URL('../Music.App/', import.meta.url);
const read = name => readFileSync(new URL(name, app), 'utf8').replaceAll('\r\n','\n');
const method = (s,a,b) => { const i=s.indexOf(a); return i<0?'':s.slice(i,s.indexOf(b,i)); };
test('actual artist detail entry, stale retrieval, retry and album Back preserve full occurrence queue and origin', () => {
  const artifacts=fileURLToPath(new URL('../artifacts/',import.meta.url)); mkdirSync(artifacts,{recursive:true});
  const dir=mkdtempSync(join(artifacts,'artists-flow-'));
  try {
    const main=read('MainWindow.cs'), nav=read('MainWindow.Navigation.cs');
    let artists=''; try { artists=read('MainWindow.Artists.cs'); } catch {}
    const links=['RequestGate.cs','NavigationHistory.cs','CollectionTransport.cs'].map(n=>`<Compile Include="${fileURLToPath(new URL(n,app))}"/>`).join('');
    writeFileSync(join(dir,'Checks.csproj'),`<Project Sdk="Microsoft.NET.Sdk"><PropertyGroup><TargetFramework>net10.0</TargetFramework><OutputType>Exe</OutputType><ImplicitUsings>enable</ImplicitUsings><Nullable>enable</Nullable><NoWarn>CS0649;CS0414</NoWarn></PropertyGroup><ItemGroup>${links}</ItemGroup></Project>`);
    writeFileSync(join(dir,'Program.cs'),`
using Music.App;
await MainWindow.Run();
record Artist(string Id,string Name,string? CoverArtId=null,int AlbumCount=0);
record Album(string Id,string Name);
record Playlist(string Id,string Name);
record PlaylistContents(Playlist Playlist,IReadOnlyList<Song> Songs);
record Song(string Id);
record ArtistLibrary(Artist Artist,IReadOnlyList<Album> Albums,IReadOnlyList<Song> Songs,bool IsComplete=true);
record ArtistNavigation(Artist Artist,string View);
record ArtistAlbumNavigation(Album Album);
class MainWindow {
${method(main,'    private async Task OpenCollectionAsync(', '    private void RenderDetail()')}
${method(main,'    private void AddCollection(', '    private FrameworkElement CardElement(')}
${method(artists,'    private async Task OpenArtistAsync(', '    private void RenderArtistHeader(')}
${method(artists,'    private void RenderArtistHeader(', '    private void InitializeArtistAlbums(')}
${method(artists,'    private Grid CreateArtistTransport()', '\n}')}
${method(nav,'    private async Task GoBackAsync()', '\n}')}
${method(nav,'    private NavigationView CaptureNavigation()', '    private void InitializeNavigation()')}
readonly NavigationHistory navigationHistory=new(); readonly RequestGate browseGate=new(), connectionGate=new(), artworkGate=new();
readonly HashSet<string> unverifiedPlaylistIds=new(), deletedPlaylistIds=new();
readonly Dictionary<string, Playlist> pendingPlaylistDeletions=new();
Root root=new(); void BindLibraryShelf(StackPanel s,ListViewBase v){} record Card(string Title,string Subtitle,string? ArtId,object Value);
void BindPlaylistContext(ListViewBase view){}
void StretchPlaylistRows(ListViewBase view){}
readonly CollectionTransport collectionTransport=new(); static object Sage=new(),Muted=new();
IReadOnlyList<Song> queue=[]; int starts; void StartQueue(IReadOnlyList<Song> tracks,int index){queue=tracks;starts++;}
FrameworkElement CardElement(Card c,string l,CancellationToken t)=>new(){Tag=c.Value};
FrameworkElement ArtistPortrait(Artist a,double s,CancellationToken t)=>new(); string Text(string s,int size,bool muted=false)=>s;
RequestGate.Lease connectionLease; NavidromeClient? client=new(); bool closed,accountChangePending,browsePending,detailLoading,detailError;
long navigationRevision,browseResultsVersion; object? detailCollection; string page="artists",query="same",mode="list",detailTitle="",artistView="albums";
List<Song> detailSongs=[]; IReadOnlyList<Album> artistAlbums=[]; bool artistSongsIncomplete; (string Kind,string Id) detailIdentity;
OfflineSettings offlineSettings=new(); Dictionary<string,object> controls=new(); int renders;
T C<T>(string n) where T:new() {if(!controls.ContainsKey(n))controls[n]=new T(); return (T)controls[n];}
void SetNavigation(){} void RenderDetail(){renders++;} void RenderOfflineBrowse(){} void ClearDetail(){} void ShowError(string s){}
void RememberCollectionMembership((string Kind, string Id) identity, IReadOnlyList<Song>? tracks){}
Task NavigateAsync(string p,bool remember=true,NavigationView? restore=null){browseGate.Cancel();page=p;return Task.CompletedTask;}
static void Check(bool b,string s){if(!b)throw new Exception(s);}
public static async Task Run(){
 var w=new MainWindow();w.connectionLease=w.connectionGate.Begin();
 var shelf=new StackPanel(); var cardArtist=new Artist("card","Same");
 w.AddCollection(shelf,new[]{new Card("Same","Artist",null,cardArtist)},"grid",CancellationToken.None);
 var cards=(ListViewBase)shelf.Children.Single();cards.Invoke(cards.Items.Single());
 Check(w.detailIdentity==("artist","card"),"native shelf callback opens artist");
 await w.NavigateAsync("albums");cards.Invoke(cards.Items.Single());
 Check(w.page=="albums","abandoned artist card must not navigate after leaving its shelf");
 w=new MainWindow();w.connectionLease=w.connectionGate.Begin();
 var a=new Artist("a","Same"); await w.OpenCollectionAsync(a);
 Check(w.page=="detail" && w.detailIdentity==("artist","a"),"artist card must open native artist detail");
 Check(w.detailSongs.Select(s=>s.Id).SequenceEqual(new[]{"s","t","s"}),"full ordered occurrence list");
 var oldHeader=new StackPanel();w.RenderArtistHeader(oldHeader);var oldTabs=(StackPanel)oldHeader.Children.Last();
 w.navigationRevision++;((Button)oldTabs.Children[1]).Invoke();
 Check(w.artistView=="albums","obsolete artist tabs cannot mutate a later visit with the same artist identity");
 var metadataHeader=new StackPanel();w.RenderArtistHeader(metadataHeader);var metadataTabs=(StackPanel)metadataHeader.Children.Last();
 w.detailCollection=new ArtistNavigation(a,"albums");((Button)metadataTabs.Children[1]).Invoke();
 Check(w.artistView=="albums","obsolete header metadata cannot replace refreshed artist metadata");
 var controls=(StackPanel)w.CreateArtistTransport().Children.Single(); var play=(Button)controls.Children[0];var shuffle=(Button)controls.Children[1];
 play.Invoke();Check(w.queue.Select(s=>s.Id).SequenceEqual(new[]{"s","t","s"})&&w.starts==1,"real Play callback starts fresh ordered queue");
 var original=w.queue;shuffle.Invoke();Check(!ReferenceEquals(original,w.queue)&&w.queue.Count(s=>s.Id=="s")==2&&w.starts==2,"real Shuffle callback preserves all occurrences");
 play.Invoke();Check(w.queue.Select(s=>s.Id).SequenceEqual(new[]{"s","t","s"}),"Play resets ordered queue after shuffle");
 int starts=w.starts;w.accountChangePending=true;play.Invoke();shuffle.Invoke();w.accountChangePending=false;
 Check(w.starts==starts,"account transition blocks captured enabled callbacks");
 w.offlineSettings.Offline=true;play.Invoke();shuffle.Invoke();w.offlineSettings.Offline=false;
 Check(w.starts==starts,"offline blocks captured callbacks");
 var prior=w.detailSongs;w.detailSongs=prior.ToList();play.Invoke();shuffle.Invoke();w.detailSongs=prior;
 Check(w.starts==starts,"replaced metadata blocks captured callbacks");
 w.page="albums";play.Invoke();shuffle.Invoke();w.page="detail";
 Check(w.starts==starts,"abandoned detail blocks captured callbacks");
 var transport=new CollectionTransport();var ordered=transport.Prepare(w.detailSongs,true,false)!;
 var shuffled=transport.Prepare(w.detailSongs,true,true,new Random(8))!;
 Check(!ReferenceEquals(ordered,w.detailSongs)&&ordered.Select(s=>s.Id).SequenceEqual(new[]{"s","t","s"}),"fresh ordered queue");
 Check(shuffled.Count(s=>s.Id=="s")==2&&shuffled.Length==3,"shuffle preserves occurrences");
 w.artistView="songs";w.detailCollection=new ArtistNavigation(a,"songs");
 var album=new Album("x","Album");
 w.client!.CollectionSongs=(_,strict)=>strict ? Task.FromException<IReadOnlyList<Song>>(new Exception()) : Task.FromResult<IReadOnlyList<Song>>([new Song("s")]);
 await w.OpenCollectionAsync(new ArtistAlbumNavigation(album));
 Check(w.detailIdentity==("album","x") && w.detailError && w.detailSongs.Count==0,"artist album open must reject a truncated result");
 await w.OpenCollectionAsync(w.detailCollection,false);
 Check(w.detailError && w.detailSongs.Count==0,"restoring artist album route preserves strict validation");
 await w.GoBackAsync();
 Check(w.detailIdentity==("artist","a") && w.artistView=="songs", "album Back restores artist tab");
 await w.GoBackAsync();Check(w.page=="artists","artist Back restores list origin");
 foreach(var stale in new[]{"navigation","account","offline"}) {
  w=new MainWindow();w.connectionLease=w.connectionGate.Begin();var pending=new TaskCompletionSource<ArtistLibrary>();
  w.client!.Artist=(_,_)=>pending.Task;var open=w.OpenCollectionAsync(a);Check(w.detailLoading,"loading before retrieval");
  var loadingControls=(StackPanel)w.CreateArtistTransport().Children.Single();foreach(Button b in loadingControls.Children)b.Invoke();
  Check(w.starts==0,"loading artist cannot replace queue");
  if(stale=="navigation")await w.NavigateAsync("albums");
  if(stale=="account"){w.connectionLease=w.connectionGate.Begin();w.client=new();}
  if(stale=="offline"){w.offlineSettings.Offline=true;w.browseGate.Cancel();}
  int renders=w.renders;pending.SetResult(NavidromeClient.Data(a));await open;
  Check(w.renders==renders && w.detailSongs.Count==0,"stale completion must not publish "+stale);
 }
 w=new MainWindow();w.connectionLease=w.connectionGate.Begin();w.client!.Artist=(_,_)=>Task.FromException<ArtistLibrary>(new Exception());
 await w.OpenCollectionAsync(a);Check(w.detailError&&!w.detailLoading&&w.detailSongs.Count==0,"failure disables complete queue");
 var failedControls=(StackPanel)w.CreateArtistTransport().Children.Single();foreach(Button b in failedControls.Children)b.Invoke();
 Check(w.starts==0,"failed retrieval never starts partial queue");
 w.client.Artist=(a,_)=>Task.FromResult(NavidromeClient.Data(new Artist(a,"Same")));
 await w.OpenCollectionAsync(a,false);Check(!w.detailError&&w.detailSongs.Count==3,"retry publishes complete result");
 w=new MainWindow();w.connectionLease=w.connectionGate.Begin();
 w.client!.Artist=(_,_)=>Task.FromResult(NavidromeClient.Data(a) with { IsComplete=false });
 await w.OpenCollectionAsync(a);
 Check(!w.detailError&&!w.detailLoading&&w.artistAlbums.Count==1&&w.detailSongs.Count==3,"partial result retains artist albums and confirmed songs");
 var partial=(StackPanel)w.CreateArtistTransport().Children.Single();
 foreach(Button b in partial.Children){Check(!b.IsEnabled,"partial artist actions must look disabled");b.Invoke();}
 Check(w.starts==0,"partial artist actions cannot start an incomplete queue");
 var header=new StackPanel();w.RenderArtistHeader(header);
 Check(header.Children.Contains("Some albums or songs could not load."),"partial artist shows an explicit warning");
 w.client.Artist=(_,_)=>Task.FromResult(NavidromeClient.Data(a));
 header.Children.OfType<Button>().Single().Invoke();
 Check(!w.artistSongsIncomplete&&!w.detailLoading,"retry replaces incomplete state with complete result");
 ((Button)((StackPanel)w.CreateArtistTransport().Children.Single()).Children[0]).Invoke();
 Check(w.starts==1,"successful retry restores playback");
 Console.WriteLine("PASS artist entry / duplicate queues / album Back / stale account-navigation-offline / failure and partial retry");
}
}
class NavidromeClient {
public Func<string,CancellationToken,Task<ArtistLibrary>> Artist=(id,_)=>Task.FromResult(Data(new Artist(id,"Same")));
public static ArtistLibrary Data(Artist a)=>new(a,new[]{new Album("x","Album")},new[]{new Song("s"),new Song("t"),new Song("s")});
public Task<ArtistLibrary> GetArtistAsync(string id,CancellationToken token)=>Artist(id,token);
public Func<string,bool,Task<IReadOnlyList<Song>>> CollectionSongs=(_,_)=>Task.FromResult<IReadOnlyList<Song>>([new Song("s")]);
public Task<IReadOnlyList<Song>> GetAlbumSongsAsync(string id,CancellationToken t,bool validateCount=false)=>CollectionSongs(id,validateCount);
public Task<IReadOnlyList<Song>> GetPlaylistSongsAsync(string id,CancellationToken t)=>GetAlbumSongsAsync(id,t);
public Task<PlaylistContents> GetPlaylistContentsAsync(string id,CancellationToken t)=>throw new Exception("Unexpected strict playlist read");
}
class OfflineSettings{public bool Offline;}
enum Visibility{Visible,Collapsed}
class ProgressBar{public Visibility Visibility;}
class TextBlock{public string Text="";}
class TextBox{public Visibility Visibility;}
class Button{
public Visibility Visibility;public object? Content,Background,Foreground;public double Width,Height,MinWidth;public CornerRadius CornerRadius;public Thickness Padding;
public bool IsEnabled;public VerticalAlignment VerticalAlignment;public event EventHandler? Click;public void Invoke()=>Click?.Invoke(this,EventArgs.Empty);
}
class FrameworkElement{public object? Tag;}
class StackPanel{public List<object> Children=new();public Orientation Orientation;public double Spacing;public HorizontalAlignment HorizontalAlignment;public VerticalAlignment VerticalAlignment;}
class Grid{public List<object> Children=new();}
class Polygon{public PointCollection? Points;public object? Fill;public Thickness Margin;}
class PointCollection:List<Windows.Foundation.Point>{}
class SolidColorBrush{public SolidColorBrush(object color){}}
static class ColorHelper{public static object FromArgb(byte a,byte r,byte g,byte b)=>new();}
record struct Thickness(double Value,double Top=0,double Right=0,double Bottom=0);
record struct CornerRadius(double Value);
enum Orientation{Horizontal}
enum VerticalAlignment{Center}
enum Symbol{Shuffle}
class SymbolIcon{public SymbolIcon(Symbol symbol){}public object? Foreground;}
static class AutomationProperties{public static void SetName(object element,string name){}public static void SetItemStatus(object element,string status){}}
static class ToolTipService{public static void SetToolTip(object element,string name){}}
class ItemsPanelTemplate{}
class Root{public Dictionary<string,object> Resources=new(){{"LibraryRowPanel",new ItemsPanelTemplate()}};}
enum ListViewSelectionMode{None}
enum HorizontalAlignment{Stretch,Left}
enum ScrollMode{Disabled,Enabled}
enum ScrollBarVisibility{Disabled,Hidden}
class ItemClickEventArgs:EventArgs{public object? ClickedItem;}
class ListViewBase{
public ListViewSelectionMode SelectionMode;public bool IsItemClickEnabled;public HorizontalAlignment HorizontalAlignment;public object? ItemsPanel;
public List<object> Items=new(); public event EventHandler<ItemClickEventArgs>? ItemClick;
public void Invoke(object item)=>ItemClick?.Invoke(this,new(){ClickedItem=item});
}
class GridView:ListViewBase{} class ListView:ListViewBase{}
class ScrollViewer{
public static void SetVerticalScrollMode(ListViewBase v,ScrollMode m){} public static void SetHorizontalScrollMode(ListViewBase v,ScrollMode m){}
public static void SetVerticalScrollBarVisibility(ListViewBase v,ScrollBarVisibility m){} public static void SetHorizontalScrollBarVisibility(ListViewBase v,ScrollBarVisibility m){}
}
namespace Windows.Foundation{record struct Point(double X,double Y);}
`);
    const result=spawnSync('dotnet',['run','--project',join(dir,'Checks.csproj'),'-c','Release'],{encoding:'utf8',timeout:120000});
    assert.equal(result.status,0,result.stdout+'\n'+result.stderr);
    assert.match(result.stdout,/PASS artist entry/);
  } finally { rmSync(dir,{recursive:true,force:true}); }
});
