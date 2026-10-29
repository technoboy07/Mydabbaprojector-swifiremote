package com.m2smartremote.core;

import java.util.List;

/**
 * Picks the next item in a direction from a set of on-screen rectangles. This is a port of the
 * scoring used by Android's own {@code android.view.FocusFinder}, so "virtual focus" moves the
 * same way hardware D-pad focus would.
 */
public final class DirectionalFinder {
    private DirectionalFinder() {
    }

    /** Returns the index of the best candidate, or -1 if none lies in that direction. */
    public static int findNext(Box source, List<Box> candidates, Direction dir) {
        int best = -1;
        Box bestBox = null;
        for (int i = 0; i < candidates.size(); i++) {
            Box c = candidates.get(i);
            if (c.equals(source) || c.isEmpty()) {
                continue;
            }
            if (bestBox == null ? isCandidate(source, c, dir) : isBetterCandidate(dir, source, c, bestBox)) {
                best = i;
                bestBox = c;
            }
        }
        return best;
    }

    /** First item in reading order (top-most, then left-most); used when nothing is focused. */
    public static int findFirst(List<Box> candidates) {
        int best = -1;
        for (int i = 0; i < candidates.size(); i++) {
            Box c = candidates.get(i);
            if (c.isEmpty()) {
                continue;
            }
            if (best < 0) {
                best = i;
                continue;
            }
            Box b = candidates.get(best);
            if (c.top < b.top || (c.top == b.top && c.left < b.left)) {
                best = i;
            }
        }
        return best;
    }

    static boolean isCandidate(Box src, Box dest, Direction dir) {
        switch (dir) {
            case LEFT:
                return (src.right > dest.right || src.left >= dest.right) && src.left > dest.left;
            case RIGHT:
                return (src.left < dest.left || src.right <= dest.left) && src.right < dest.right;
            case UP:
                return (src.bottom > dest.bottom || src.top >= dest.bottom) && src.top > dest.top;
            case DOWN:
            default:
                return (src.top < dest.top || src.bottom <= dest.top) && src.bottom < dest.bottom;
        }
    }

    static boolean isBetterCandidate(Direction dir, Box src, Box rect1, Box rect2) {
        if (!isCandidate(src, rect1, dir)) {
            return false;
        }
        if (!isCandidate(src, rect2, dir)) {
            return true;
        }
        if (beamBeats(dir, src, rect1, rect2)) {
            return true;
        }
        if (beamBeats(dir, src, rect2, rect1)) {
            return false;
        }
        return weightedDistance(dir, src, rect1) < weightedDistance(dir, src, rect2);
    }

    static boolean beamBeats(Direction dir, Box src, Box rect1, Box rect2) {
        boolean rect1InBeam = beamsOverlap(dir, src, rect1);
        boolean rect2InBeam = beamsOverlap(dir, src, rect2);
        if (rect2InBeam || !rect1InBeam) {
            return false;
        }
        if (!isToDirectionOf(dir, src, rect2)) {
            return true;
        }
        if (dir.isHorizontal()) {
            return true;
        }
        return majorAxisDistance(dir, src, rect1) < majorAxisDistanceToFarEdge(dir, src, rect2);
    }

    static boolean beamsOverlap(Direction dir, Box r1, Box r2) {
        if (dir.isHorizontal()) {
            return r2.bottom > r1.top && r2.top < r1.bottom;
        }
        return r2.right > r1.left && r2.left < r1.right;
    }

    static boolean isToDirectionOf(Direction dir, Box src, Box dest) {
        switch (dir) {
            case LEFT:
                return src.left >= dest.right;
            case RIGHT:
                return src.right <= dest.left;
            case UP:
                return src.top >= dest.bottom;
            case DOWN:
            default:
                return src.bottom <= dest.top;
        }
    }

    static long majorAxisDistance(Direction dir, Box src, Box dest) {
        long d;
        switch (dir) {
            case LEFT:
                d = src.left - dest.right;
                break;
            case RIGHT:
                d = dest.left - src.right;
                break;
            case UP:
                d = src.top - dest.bottom;
                break;
            case DOWN:
            default:
                d = dest.top - src.bottom;
                break;
        }
        return Math.max(0, d);
    }

    static long majorAxisDistanceToFarEdge(Direction dir, Box src, Box dest) {
        long d;
        switch (dir) {
            case LEFT:
                d = src.left - dest.left;
                break;
            case RIGHT:
                d = dest.right - src.right;
                break;
            case UP:
                d = src.top - dest.top;
                break;
            case DOWN:
            default:
                d = dest.bottom - src.bottom;
                break;
        }
        return Math.max(1, d);
    }

    static long minorAxisDistance(Direction dir, Box src, Box dest) {
        if (dir.isHorizontal()) {
            return Math.abs((long) src.centerY() - dest.centerY());
        }
        return Math.abs((long) src.centerX() - dest.centerX());
    }

    static long weightedDistance(Direction dir, Box src, Box dest) {
        long major = majorAxisDistance(dir, src, dest);
        long minor = minorAxisDistance(dir, src, dest);
        return 13 * major * major + minor * minor;
    }
}
