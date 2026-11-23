using System.Buffers.Binary;
using System.Runtime.InteropServices;
using Microsoft.UI.Dispatching;

namespace Music.App;

// Explorer owns the preview. Keep its command IDs stable for the lifetime of the window.
internal sealed partial class TaskbarPlayback : IDisposable
{
    private const uint WmCommand = 0x0111, WmNcDestroy = 0x0082, WmDpiChanged = 0x02E0;
    private const uint ThumbnailClicked = 0x1800, Disabled = 0x1;
    private const uint PreviousId = 0x4101, ToggleId = 0x4102, NextId = 0x4103;
    private const uint SubclassId = 0x4D555349; // MUSI
    private static readonly Guid TaskbarListClass = new("56FDF344-FD6D-11D0-958A-006097C9A090");
    private static readonly Guid TaskbarList3Interface = new("EA1AFB91-9E28-4B86-90E9-9E9F8A5EEFAF");

    private readonly nint hwnd;
    private readonly uint taskbarButtonCreated;
    private readonly DispatcherQueue dispatcher;
    private readonly SubclassProc subclass;
    private readonly Action previous, toggle, next;
    private bool disposed, ready, added, publishing, publishQueued, enabled, playing;
    private uint shellGeneration;
    private (bool Enabled, bool Playing, uint Dpi)? published;

    private TaskbarPlayback(nint hwnd, uint message, DispatcherQueue dispatcher, Action previous, Action toggle, Action next)
    {
        this.hwnd = hwnd;
        taskbarButtonCreated = message;
        this.dispatcher = dispatcher;
        this.previous = previous;
        this.toggle = toggle;
        this.next = next;
        subclass = WindowProc;
    }

    public static TaskbarPlayback? Attach(nint hwnd, DispatcherQueue dispatcher, Action previous, Action toggle, Action next)
    {
        uint message = RegisterWindowMessage("TaskbarButtonCreated");
        if (hwnd == 0 || message == 0) return null;
        var toolbar = new TaskbarPlayback(hwnd, message, dispatcher, previous, toggle, next);
        return SetWindowSubclass(hwnd, toolbar.subclass, SubclassId, 0) ? toolbar : null;
    }

    public void Update(bool enabled, bool playing)
    {
        this.enabled = enabled;
        this.playing = enabled && playing;
        QueuePublish();
    }

    private void QueuePublish()
    {
        if (disposed || !ready || publishQueued) return;
        // Shell calls can pump messages. Do not make them inside a transport action.
        publishQueued = dispatcher.TryEnqueue(() => { publishQueued = false; Publish(); });
    }

    private nint WindowProc(nint window, uint message, nuint wParam, nint lParam, nuint id, nuint data)
    {
        if (message == taskbarButtonCreated)
        {
            // A new Explorer taskbar starts without our toolbar, including after a restart.
            shellGeneration++;
            ready = true;
            added = false;
            published = null;
            QueuePublish();
        }
        else if (message == WmDpiChanged) QueuePublish();
        else if (message == WmNcDestroy) Dispose();
        else if (message == WmCommand && ((wParam >> 16) & 0xFFFF) == ThumbnailClicked)
        {
            var action = (wParam & 0xFFFF) switch
            {
                PreviousId => previous,
                ToggleId => toggle,
                NextId => next,
                _ => null
            };
            if (action is not null)
            {
                uint generation = shellGeneration;
                if (!disposed && added && enabled)
                    dispatcher.TryEnqueue(() => { if (!disposed && added && enabled && generation == shellGeneration) action(); });
                return 0;
            }
        }
        return DefSubclassProc(window, message, wParam, lParam);
    }

    private unsafe void Publish()
    {
        if (disposed || !ready || publishing) return;
        var state = (Enabled: enabled, Playing: playing, Dpi: GetDpiForWindow(hwnd));
        if (state.Dpi == 0 || published == state) return;
        if (CoCreateInstance(in TaskbarListClass, 0, 1 /* in-process */, in TaskbarList3Interface, out nint list) < 0) return;
        var table = *(nint**)list;
        var initialize = (delegate* unmanaged[Stdcall]<nint, int>)table[3]; // ITaskbarList::HrInit
        // IUnknown (3) + ITaskbarList (5) + ITaskbarList2 (1) + preceding ITaskbarList3 methods (6).
        var apply = (delegate* unmanaged[Stdcall]<nint, nint, uint, ThumbButton*, int>)table[added ? 16 : 15];
        uint generation = shellGeneration;
        var buttons = stackalloc ThumbButton[3];
        new Span<ThumbButton>(buttons, 3).Clear();
        publishing = true;
        try
        {
            if (initialize(list) < 0) return;
            int width = GetSystemMetricsForDpi(11 /* SM_CXICON */, state.Dpi);
            int height = GetSystemMetricsForDpi(12 /* SM_CYICON */, state.Dpi);
            if (width <= 0 || height <= 0) return;
            SetButton(ref buttons[0], PreviousId, "Previous", Glyph.Previous, state.Enabled, width, height);
            SetButton(ref buttons[1], ToggleId, state.Playing ? "Pause" : "Play",
                state.Playing ? Glyph.Pause : Glyph.Play, state.Enabled, width, height);
            SetButton(ref buttons[2], NextId, "Next", Glyph.Next, state.Enabled, width, height);
            if (!disposed && generation == shellGeneration && buttons[0].Icon != 0 && buttons[1].Icon != 0 && buttons[2].Icon != 0 &&
                apply(list, hwnd, 3, buttons) >= 0 && !disposed && generation == shellGeneration)
            {
                added = true;
                published = state;
            }
        }
        finally
        {
            // Explorer copies the icons on success; these handles are always ours to free.
            for (int i = 0; i < 3; i++) if (buttons[i].Icon != 0) DestroyIcon(buttons[i].Icon);
            Marshal.Release(list);
            publishing = false;
        }
    }

    public void Dispose()
    {
        if (disposed) return;
        disposed = true;
        RemoveWindowSubclass(hwnd, subclass, SubclassId);
    }

    [StructLayout(LayoutKind.Sequential, Pack = 8)]
    private unsafe struct ThumbButton
    {
        public uint Mask, Id, Bitmap;
        public nint Icon;
        public fixed char Tip[260]; // WCHAR[260] in the native THUMBBUTTON ABI
        public uint Flags;
    }

    private static unsafe void SetButton(ref ThumbButton button, uint id, string tip, Glyph glyph, bool enabled, int width, int height)
    {
        button.Mask = 0x2 | 0x4 | 0x8; // THB_ICON | THB_TOOLTIP | THB_FLAGS
        button.Id = id;
        button.Flags = enabled ? 0u : Disabled;
        var bits = RenderIcon(glyph, width, height);
        fixed (char* text = button.Tip) tip.AsSpan().CopyTo(new Span<char>(text, 259));
        button.Icon = CreateIconFromResourceEx(bits, (uint)bits.Length, true, 0x00030000, width, height, 0);
    }

    private enum Glyph { Previous, Play, Pause, Next }

    private static byte[] RenderIcon(Glyph glyph, int width, int height)
    {
        // A 32-bit RT_ICON DIB: header, bottom-up BGRA pixels, then a 1-bit AND mask.
        // The neutral light fill and dark outline keep the shapes legible on either shell theme.
        const int header = 40;
        int maskStride = ((width + 31) / 32) * 4, maskStart = header + width * height * 4;
        var bits = new byte[maskStart + maskStride * height];
        BinaryPrimitives.WriteInt32LittleEndian(bits, header);
        BinaryPrimitives.WriteInt32LittleEndian(bits.AsSpan(4), width);
        BinaryPrimitives.WriteInt32LittleEndian(bits.AsSpan(8), height * 2);
        BinaryPrimitives.WriteUInt16LittleEndian(bits.AsSpan(12), 1);
        BinaryPrimitives.WriteUInt16LittleEndian(bits.AsSpan(14), 32);
        for (int row = 0; row < height; row++)
        for (int col = 0; col < width; col++)
        {
            double x = (col + .5) * 32 / width, y = (row + .5) * 32 / height;
            if (!Contains(glyph, x, y))
            {
                bits[maskStart + row * maskStride + col / 8] |= (byte)(0x80 >> (col % 8));
                continue;
            }
            bool interior = Contains(glyph, x - 1, y) && Contains(glyph, x + 1, y) &&
                Contains(glyph, x, y - 1) && Contains(glyph, x, y + 1);
            int i = header + (row * width + col) * 4;
            bits[i] = bits[i + 1] = bits[i + 2] = interior ? (byte)242 : (byte)35;
            bits[i + 3] = 255;
        }
        return bits;
    }

    private static bool Contains(Glyph glyph, double x, double y)
    {
        if (y is < 6 or > 26) return false;
        if (glyph == Glyph.Pause) return x is (>= 8 and <= 13) or (>= 19 and <= 24);
        if (glyph == Glyph.Previous) x = 32 - x;
        if (glyph == Glyph.Play) return x >= 9 && x <= 25 - 1.6 * Math.Abs(y - 16);
        return (x >= 6 && x <= 21 - 1.5 * Math.Abs(y - 16)) || x is >= 22 and <= 26;
    }

    [UnmanagedFunctionPointer(CallingConvention.Winapi)]
    private delegate nint SubclassProc(nint hwnd, uint message, nuint wParam, nint lParam, nuint id, nuint data);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern uint RegisterWindowMessage(string name);
    [DllImport("comctl32.dll", ExactSpelling = true)]
    private static extern bool SetWindowSubclass(nint hwnd, SubclassProc proc, nuint id, nuint data);
    [DllImport("comctl32.dll", ExactSpelling = true)]
    private static extern bool RemoveWindowSubclass(nint hwnd, SubclassProc proc, nuint id);
    [DllImport("comctl32.dll", ExactSpelling = true)]
    private static extern nint DefSubclassProc(nint hwnd, uint message, nuint wParam, nint lParam);
    [DllImport("ole32.dll", ExactSpelling = true)]
    private static extern int CoCreateInstance(in Guid clsid, nint outer, uint context, in Guid iid, out nint instance);
    [DllImport("user32.dll", ExactSpelling = true)]
    private static extern uint GetDpiForWindow(nint hwnd);
    [DllImport("user32.dll", ExactSpelling = true)]
    private static extern int GetSystemMetricsForDpi(int index, uint dpi);
    [DllImport("user32.dll", ExactSpelling = true)]
    private static extern nint CreateIconFromResourceEx(byte[] bits, uint size, bool icon, uint version, int width, int height, uint flags);
    [DllImport("user32.dll", ExactSpelling = true)]
    private static extern bool DestroyIcon(nint icon);
}
