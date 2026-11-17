namespace Music.Core;

/// <summary>Wheel routing in device-independent pixels; never snaps or quantizes small deltas.</summary>
public static class LibraryShelfScrollPolicy
{
    public readonly record struct Motion(bool Horizontal, double Delta);
    public readonly record struct ArrowState(bool Previous, bool Next);

    public static Motion Wheel(int delta, bool shift, bool horizontal) =>
        new(shift || horizontal, horizontal ? delta : -delta);

    public static double Clamp(double offset, double maximum) => Math.Clamp(offset, 0, Math.Max(0, maximum));

    public static ArrowState Boundaries(double offset, double maximum)
    {
        double position = Clamp(offset, maximum);
        return new(position > 0, position < Math.Max(0, maximum));
    }
}
