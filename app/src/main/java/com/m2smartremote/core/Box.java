package com.m2smartremote.core;

/** Immutable screen rectangle (android.graphics.Rect is not usable in JVM unit tests). */
public final class Box {
    public final int left;
    public final int top;
    public final int right;
    public final int bottom;

    public Box(int left, int top, int right, int bottom) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }

    public int width() {
        return right - left;
    }

    public int height() {
        return bottom - top;
    }

    public boolean isEmpty() {
        return right <= left || bottom <= top;
    }

    public int centerX() {
        return (left + right) / 2;
    }

    public int centerY() {
        return (top + bottom) / 2;
    }

    public long area() {
        return isEmpty() ? 0 : (long) width() * height();
    }

    public boolean contains(int x, int y) {
        return x >= left && x < right && y >= top && y < bottom;
    }

    public boolean intersects(Box o) {
        return left < o.right && o.left < right && top < o.bottom && o.top < bottom;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Box)) {
            return false;
        }
        Box b = (Box) o;
        return left == b.left && top == b.top && right == b.right && bottom == b.bottom;
    }

    @Override
    public int hashCode() {
        return ((left * 31 + top) * 31 + right) * 31 + bottom;
    }

    @Override
    public String toString() {
        return "Box[" + left + "," + top + "," + right + "," + bottom + "]";
    }
}
