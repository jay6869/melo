package melo.term;

import java.io.IOException;
import java.io.InputStream;
import java.util.function.Consumer;

import melo.term.Key.Type;

/** Decodes raw TTY bytes (UTF-8, CSI/SS3 sequences, SGR mouse) into {@link Key}s. */
public final class Input implements Runnable {
    private final InputStream in;
    private final Consumer<Key> sink;

    public Input(InputStream in, Consumer<Key> sink) {
        this.in = in;
        this.sink = sink;
    }

    @Override
    public void run() {
        try {
            int b;
            while ((b = in.read()) >= 0) {
                Key k = decode(b);
                if (k != null) sink.accept(k);
            }
        } catch (IOException ignored) {
        }
    }

    private Key decode(int b) throws IOException {
        if (b == 0x1b) return escape();
        if (b == '\r' || b == '\n') return Key.of(Type.ENTER);
        if (b == '\t') return Key.of(Type.TAB);
        if (b == 127 || b == 8) return Key.of(Type.BACKSPACE);
        if (b == 0) return Key.ctrl(' ');
        if (b < 32) return Key.ctrl('a' + b - 1);
        return Key.chr(utf8(b));
    }

    private int utf8(int b) throws IOException {
        int n, cp;
        if ((b & 0x80) == 0) return b;
        if ((b & 0xE0) == 0xC0) {
            n = 1;
            cp = b & 0x1F;
        } else if ((b & 0xF0) == 0xE0) {
            n = 2;
            cp = b & 0x0F;
        } else if ((b & 0xF8) == 0xF0) {
            n = 3;
            cp = b & 0x07;
        } else {
            return 0xFFFD;
        }
        for (int i = 0; i < n; i++) {
            int c = in.read();
            if (c < 0 || (c & 0xC0) != 0x80) return 0xFFFD;
            cp = (cp << 6) | (c & 0x3F);
        }
        return cp;
    }

    /** A lone ESC is only a key if nothing else arrives right behind it. */
    private boolean more() throws IOException {
        if (in.available() > 0) return true;
        try {
            Thread.sleep(25);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return in.available() > 0;
    }

    private Key escape() throws IOException {
        if (!more()) return Key.of(Type.ESC);
        int c = in.read();
        if (c == '[') return csi();
        if (c == 'O') {
            int d = in.read();
            return switch (d) {
                case 'A' -> Key.of(Type.UP);
                case 'B' -> Key.of(Type.DOWN);
                case 'C' -> Key.of(Type.RIGHT);
                case 'D' -> Key.of(Type.LEFT);
                case 'H' -> Key.of(Type.HOME);
                case 'F' -> Key.of(Type.END);
                case 'P', 'Q', 'R', 'S' -> new Key(Type.F, d - 'P' + 1, false, false, false, 0, 0);
                default -> null;
            };
        }
        if (c == 0x1b) return Key.of(Type.ESC);
        Key k = decode(c);
        return k == null ? null : k.withMods(false, true, false);
    }

    private Key csi() throws IOException {
        StringBuilder params = new StringBuilder();
        int c;
        while (true) {
            c = in.read();
            if (c < 0) return null;
            if (c >= 0x40 && c <= 0x7E) break;
            params.append((char) c);
            if (params.length() > 32) return null;
        }
        String ps = params.toString();
        if (ps.startsWith("<")) return mouse(ps.substring(1), c);

        String[] parts = ps.isEmpty() ? new String[0] : ps.split(";");
        int p0 = parts.length > 0 ? num(parts[0], 1) : 1;
        int mod = parts.length > 1 ? num(parts[1], 1) - 1 : 0;
        boolean shift = (mod & 1) != 0, alt = (mod & 2) != 0, ctrl = (mod & 4) != 0;

        Type t = switch (c) {
            case 'A' -> Type.UP;
            case 'B' -> Type.DOWN;
            case 'C' -> Type.RIGHT;
            case 'D' -> Type.LEFT;
            case 'H' -> Type.HOME;
            case 'F' -> Type.END;
            case 'Z' -> Type.BACKTAB;
            case '~' -> switch (p0) {
                case 1, 7 -> Type.HOME;
                case 2 -> Type.INSERT;
                case 3 -> Type.DELETE;
                case 4, 8 -> Type.END;
                case 5 -> Type.PAGE_UP;
                case 6 -> Type.PAGE_DOWN;
                default -> null;
            };
            default -> null;
        };
        return t == null ? null : new Key(t, 0, ctrl, alt, shift, 0, 0);
    }

    private static Key mouse(String ps, int fin) {
        String[] a = ps.split(";");
        if (a.length < 3) return null;
        int b = num(a[0], 0), x = num(a[1], 1) - 1, y = num(a[2], 1) - 1;
        Type t;
        if ((b & 64) != 0) t = (b & 1) == 0 ? Type.WHEEL_UP : Type.WHEEL_DOWN;
        else if ((b & 32) != 0) t = Type.MOUSE_DRAG;
        else t = fin == 'M' ? Type.MOUSE_DOWN : Type.MOUSE_UP;
        return new Key(t, b & 3, (b & 16) != 0, (b & 8) != 0, (b & 4) != 0, x, y);
    }

    private static int num(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
