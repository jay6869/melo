package melo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import melo.source.Track;

import static java.nio.charset.StandardCharsets.UTF_8;

/** Liked songs and play history, as TSV files under ~/.local/share/melo. */
final class Library {
    private static final int HISTORY_MAX = 300;

    private final Path likedFile, historyFile;
    private final List<Track> liked = new ArrayList<>(), history = new ArrayList<>();
    private final Set<String> likedIds = new HashSet<>();

    Library(Path dir) {
        likedFile = dir.resolve("liked.tsv");
        historyFile = dir.resolve("history.tsv");
    }

    void load() {
        liked.addAll(read(likedFile));
        history.addAll(read(historyFile));
        for (Track t : liked) likedIds.add(t.id());
    }

    List<Track> liked() {
        return Collections.unmodifiableList(liked);
    }

    List<Track> history() {
        return Collections.unmodifiableList(history);
    }

    boolean isLiked(String id) {
        return likedIds.contains(id);
    }

    /** @return true if the track is now liked */
    boolean toggleLike(Track t) {
        boolean now;
        if (likedIds.remove(t.id())) {
            liked.removeIf(x -> x.id().equals(t.id()));
            now = false;
        } else {
            likedIds.add(t.id());
            liked.add(0, t);
            now = true;
        }
        write(likedFile, liked);
        return now;
    }

    void addHistory(Track t) {
        history.removeIf(x -> x.id().equals(t.id()));
        history.add(0, t);
        while (history.size() > HISTORY_MAX) history.remove(history.size() - 1);
        write(historyFile, history);
    }

    private static List<Track> read(Path file) {
        List<Track> out = new ArrayList<>();
        if (!Files.exists(file)) return out;
        try {
            for (String line : Files.readAllLines(file, UTF_8)) {
                String[] f = line.split("\t", -1);
                if (f.length < 7 || f[0].isEmpty()) continue;
                long dur;
                try {
                    dur = Long.parseLong(f[5]);
                } catch (NumberFormatException e) {
                    dur = 0;
                }
                out.add(new Track(f[0], f[1], f[2], f[3], nul(f[4]), dur, nul(f[6])));
            }
        } catch (IOException e) {
            Log.error("reading " + file, e);
        }
        return out;
    }

    private static void write(Path file, List<Track> tracks) {
        StringBuilder sb = new StringBuilder();
        for (Track t : tracks) {
            sb.append(clean(t.id())).append('\t').append(clean(t.url())).append('\t').append(clean(t.title())).append('\t')
                    .append(clean(t.artist())).append('\t').append(clean(t.album())).append('\t').append(t.duration())
                    .append('\t').append(clean(t.thumb())).append('\n');
        }
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, sb, UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Log.error("writing " + file, e);
        }
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    private static String nul(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
