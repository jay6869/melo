package melo.term;

/** Terminal column width of code points (a compact take on wcwidth). */
public final class WcWidth {
    private WcWidth() {
    }

    public static int of(int cp) {
        if (cp == 0) return 0;
        if (cp < 32 || (cp >= 0x7f && cp < 0xa0)) return 0;
        if (zero(cp)) return 0;
        return wide(cp) ? 2 : 1;
    }

    public static int width(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            w += of(cp);
        }
        return w;
    }

    /** Truncates to at most {@code max} columns, ending with an ellipsis when cut. */
    public static String fit(String s, int max) {
        if (s == null || max <= 0) return "";
        if (width(s) <= max) return s;
        StringBuilder sb = new StringBuilder();
        int w = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            int cw = of(cp);
            if (w + cw > max - 1) break;
            sb.appendCodePoint(cp);
            w += cw;
        }
        return sb.append('…').toString();
    }

    private static boolean zero(int cp) {
        return (cp >= 0x0300 && cp <= 0x036F) || (cp >= 0x0483 && cp <= 0x0489)
                || (cp >= 0x0591 && cp <= 0x05BD) || (cp >= 0x0610 && cp <= 0x061A)
                || (cp >= 0x064B && cp <= 0x065F) || (cp >= 0x0900 && cp <= 0x0903)
                || (cp >= 0x093A && cp <= 0x094F && cp != 0x093D) || (cp >= 0x0E31 && cp <= 0x0E3A && cp != 0x0E32 && cp != 0x0E33)
                || (cp >= 0x0E47 && cp <= 0x0E4E) || (cp >= 0x1AB0 && cp <= 0x1AFF)
                || (cp >= 0x1DC0 && cp <= 0x1DFF) || (cp >= 0x200B && cp <= 0x200F)
                || (cp >= 0x202A && cp <= 0x202E) || (cp >= 0x2060 && cp <= 0x2064)
                || (cp >= 0x20D0 && cp <= 0x20FF) || (cp >= 0xFE00 && cp <= 0xFE0F)
                || (cp >= 0xFE20 && cp <= 0xFE2F) || cp == 0xFEFF
                || (cp >= 0x1F3FB && cp <= 0x1F3FF) || (cp >= 0xE0000 && cp <= 0xE01EF);
    }

    private static boolean wide(int cp) {
        return (cp >= 0x1100 && cp <= 0x115F) || cp == 0x2329 || cp == 0x232A
                || (cp >= 0x231A && cp <= 0x231B) || (cp >= 0x23E9 && cp <= 0x23EC) || cp == 0x23F0 || cp == 0x23F3
                || (cp >= 0x25FD && cp <= 0x25FE) || (cp >= 0x2614 && cp <= 0x2615) || (cp >= 0x2648 && cp <= 0x2653)
                || cp == 0x267F || cp == 0x2693 || cp == 0x26A1 || (cp >= 0x26AA && cp <= 0x26AB)
                || (cp >= 0x26BD && cp <= 0x26BE) || (cp >= 0x26C4 && cp <= 0x26C5) || cp == 0x26CE || cp == 0x26D4
                || cp == 0x26EA || (cp >= 0x26F2 && cp <= 0x26F3) || cp == 0x26F5 || cp == 0x26FA || cp == 0x26FD
                || cp == 0x2705 || (cp >= 0x270A && cp <= 0x270B) || cp == 0x2728 || cp == 0x274C || cp == 0x274E
                || (cp >= 0x2753 && cp <= 0x2755) || cp == 0x2757 || (cp >= 0x2795 && cp <= 0x2797)
                || cp == 0x27B0 || cp == 0x27BF || (cp >= 0x2B1B && cp <= 0x2B1C) || cp == 0x2B50 || cp == 0x2B55
                || (cp >= 0x2E80 && cp <= 0x303E) || (cp >= 0x3041 && cp <= 0x33FF) || (cp >= 0x3400 && cp <= 0x4DBF)
                || (cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0xA000 && cp <= 0xA4CF) || (cp >= 0xA960 && cp <= 0xA97F)
                || (cp >= 0xAC00 && cp <= 0xD7A3) || (cp >= 0xF900 && cp <= 0xFAFF) || (cp >= 0xFE10 && cp <= 0xFE19)
                || (cp >= 0xFE30 && cp <= 0xFE6F) || (cp >= 0xFF00 && cp <= 0xFF60) || (cp >= 0xFFE0 && cp <= 0xFFE6)
                || (cp >= 0x16FE0 && cp <= 0x18AFF) || (cp >= 0x1B000 && cp <= 0x1B2FF)
                || cp == 0x1F004 || cp == 0x1F0CF || cp == 0x1F18E || (cp >= 0x1F191 && cp <= 0x1F19A)
                || (cp >= 0x1F200 && cp <= 0x1F251) || (cp >= 0x1F300 && cp <= 0x1F64F)
                || (cp >= 0x1F680 && cp <= 0x1F6FF) || (cp >= 0x1F7E0 && cp <= 0x1F7EB)
                || (cp >= 0x1F90C && cp <= 0x1F9FF) || (cp >= 0x1FA70 && cp <= 0x1FAFF)
                || (cp >= 0x20000 && cp <= 0x3FFFD);
    }
}
