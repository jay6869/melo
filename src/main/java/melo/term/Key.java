package melo.term;

public record Key(Type type, int ch, boolean ctrl, boolean alt, boolean shift, int x, int y) {
    public enum Type {
        CHAR, ENTER, ESC, BACKSPACE, TAB, BACKTAB,
        UP, DOWN, LEFT, RIGHT, HOME, END, PAGE_UP, PAGE_DOWN, DELETE, INSERT, F,
        MOUSE_DOWN, MOUSE_UP, MOUSE_DRAG, WHEEL_UP, WHEEL_DOWN
    }

    public static Key of(Type t) {
        return new Key(t, 0, false, false, false, 0, 0);
    }

    public static Key chr(int cp) {
        return new Key(Type.CHAR, cp, false, false, false, 0, 0);
    }

    public static Key ctrl(int cp) {
        return new Key(Type.CHAR, cp, true, false, false, 0, 0);
    }

    public Key withMods(boolean ctrl, boolean alt, boolean shift) {
        return new Key(type, ch, this.ctrl || ctrl, this.alt || alt, this.shift || shift, x, y);
    }

    public boolean is(char c) {
        return type == Type.CHAR && !ctrl && !alt && ch == c;
    }

    public boolean isCtrl(char c) {
        return type == Type.CHAR && ctrl && ch == c;
    }

    public boolean isMouse() {
        return switch (type) {
            case MOUSE_DOWN, MOUSE_UP, MOUSE_DRAG, WHEEL_UP, WHEEL_DOWN -> true;
            default -> false;
        };
    }
}
