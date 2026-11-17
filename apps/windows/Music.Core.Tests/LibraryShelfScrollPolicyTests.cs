using Xunit;

namespace Music.Core.Tests;

public sealed class LibraryShelfScrollPolicyTests
{
    [Theory]
    [InlineData(120, false, false, false, -120)]
    [InlineData(-120, false, false, false, 120)]
    [InlineData(120, true, false, true, -120)]
    [InlineData(-120, true, false, true, 120)]
    [InlineData(120, false, true, true, 120)]
    [InlineData(-120, false, true, true, -120)]
    [InlineData(20, true, true, true, 20)]
    public void Wheel_routes_and_preserves_native_direction(int delta, bool shift, bool horizontal, bool expectedHorizontal, double expectedDelta)
    {
        var motion = LibraryShelfScrollPolicy.Wheel(delta, shift, horizontal);
        Assert.Equal(expectedHorizontal, motion.Horizontal);
        Assert.Equal(expectedDelta, motion.Delta);
    }

    [Theory]
    [InlineData(true, false)]
    [InlineData(false, true)]
    public void Small_deltas_accumulate_without_snapping(bool shift, bool horizontal)
    {
        double offset = 0;
        for (int i = 0; i < 5; i++)
        {
            var motion = LibraryShelfScrollPolicy.Wheel(horizontal ? 20 : -20, shift, horizontal);
            offset = LibraryShelfScrollPolicy.Clamp(offset + motion.Delta, 1000);
        }
        Assert.Equal(100, offset);
    }

    [Theory]
    [InlineData(-20, 500, 0, false, true)]
    [InlineData(0, 500, 0, false, true)]
    [InlineData(20, 500, 20, true, true)]
    [InlineData(500, 500, 500, true, false)]
    [InlineData(520, 500, 500, true, false)]
    [InlineData(10, 0, 0, false, false)]
    [InlineData(10, -10, 0, false, false)]
    public void Clamp_and_arrow_boundaries_follow_scrollable_width(double offset, double maximum, double expected, bool previous, bool next)
    {
        Assert.Equal(expected, LibraryShelfScrollPolicy.Clamp(offset, maximum));
        var buttons = LibraryShelfScrollPolicy.Boundaries(offset, maximum);
        Assert.Equal(previous, buttons.Previous);
        Assert.Equal(next, buttons.Next);
    }
}
