package com.m2smartremote.core;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class DirectionalFinderTest {
    // 3x3 grid of 100x100 tiles with 20px gaps, like a launcher.
    private static Box tile(int col, int row) {
        int l = 20 + col * 120;
        int t = 20 + row * 120;
        return new Box(l, t, l + 100, t + 100);
    }

    private final List<Box> grid = Arrays.asList(
            tile(0, 0), tile(1, 0), tile(2, 0),
            tile(0, 1), tile(1, 1), tile(2, 1),
            tile(0, 2), tile(1, 2), tile(2, 2));

    @Test
    public void movesToGridNeighbours() {
        Box center = tile(1, 1);
        assertEquals(1, DirectionalFinder.findNext(center, grid, Direction.UP));
        assertEquals(7, DirectionalFinder.findNext(center, grid, Direction.DOWN));
        assertEquals(3, DirectionalFinder.findNext(center, grid, Direction.LEFT));
        assertEquals(5, DirectionalFinder.findNext(center, grid, Direction.RIGHT));
    }

    @Test
    public void edgeReturnsNone() {
        assertEquals(-1, DirectionalFinder.findNext(tile(0, 0), grid, Direction.UP));
        assertEquals(-1, DirectionalFinder.findNext(tile(0, 0), grid, Direction.LEFT));
        assertEquals(-1, DirectionalFinder.findNext(tile(2, 2), grid, Direction.RIGHT));
    }

    @Test
    public void prefersItemInBeamOverCloserDiagonal() {
        Box src = new Box(0, 0, 100, 50);
        Box inBeamFar = new Box(400, 10, 500, 40);
        Box diagonalNear = new Box(120, 200, 220, 250);
        assertEquals(0, DirectionalFinder.findNext(src, Arrays.asList(inBeamFar, diagonalNear), Direction.RIGHT));
    }

    @Test
    public void findFirstIsTopLeft() {
        assertEquals(0, DirectionalFinder.findFirst(grid));
        assertEquals(-1, DirectionalFinder.findFirst(Arrays.<Box>asList()));
    }

    @Test
    public void emptyBoxesAreIgnored() {
        List<Box> list = Arrays.asList(new Box(200, 0, 200, 50), new Box(300, 0, 400, 50));
        assertEquals(1, DirectionalFinder.findNext(new Box(0, 0, 100, 50), list, Direction.RIGHT));
    }
}
