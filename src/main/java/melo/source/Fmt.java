package melo.source;

public final class Fmt {
    private Fmt() {
    }

    public static String time(double sec) {
        if (sec <= 0 || Double.isNaN(sec)) return sec == 0 ? "0:00" : "--:--";
        long s = (long) sec;
        long h = s / 3600, m = (s % 3600) / 60, r = s % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, r) : String.format("%d:%02d", m, r);
    }

    /** "1 hr 12 min" style, for totals. */
    public static String span(long sec) {
        long m = sec / 60;
        if (m < 60) return m + " min";
        return (m / 60) + " hr " + (m % 60) + " min";
    }
}
