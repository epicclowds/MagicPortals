package magicportals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/* Austausch über einen gemeinsamen Ordner. Nur geeignet, wenn alle Server auf demselben Rechner laufen. */
final class DateiAustausch implements Austausch {
    private final Path ordner;

    DateiAustausch(Path ordner) throws IOException {
        this.ordner = ordner;
        Files.createDirectories(ordner);
    }

    private static long stand(Path p) throws IOException { return Files.exists(p) ? Files.getLastModifiedTime(p).toMillis() : 0; }

    private void schreiben(Path ziel, byte[] daten) throws IOException {
        Path tmp = ziel.resolveSibling(ziel.getFileName() + ".part");
        Files.write(tmp, daten);
        Files.move(tmp, ziel, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    @Override public void lebenszeichen(String server) throws IOException { Files.write(ordner.resolve(server + ".online"), new byte[0]); }
    @Override public void abmelden(String server) throws IOException { Files.deleteIfExists(ordner.resolve(server + ".online")); }

    @Override public Set<String> online() throws IOException {
        var ergebnis = new HashSet<String>();
        long jetzt = System.currentTimeMillis();
        try (var dateien = Files.list(ordner)) {
            for (Path p : dateien.toList()) {
                String n = p.getFileName().toString();
                if (n.endsWith(".online") && jetzt - stand(p) < GUELTIG_ONLINE) ergebnis.add(n.substring(0, n.length() - 7));
            }
        }
        return ergebnis;
    }

    @Override public void ankunftSetzen(String ziel, UUID spieler, String herkunft) throws IOException {
        Files.writeString(ordner.resolve(ziel + "-" + spieler + ".ankunft"), herkunft, StandardCharsets.UTF_8);
    }

    @Override public String ankunftHolen(String server, UUID spieler) throws IOException {
        Path marke = ordner.resolve(server + "-" + spieler + ".ankunft");
        if (!Files.exists(marke)) return null;
        boolean frisch = System.currentTimeMillis() - stand(marke) < GUELTIG_ANKUNFT;
        String herkunft = Files.readString(marke, StandardCharsets.UTF_8).trim();
        Files.deleteIfExists(marke);
        return frisch ? herkunft : null;
    }

    @Override public void aufnahmeSpeichern(String schluessel, String art, byte[] daten) throws IOException {
        schreiben(ordner.resolve(schluessel + "." + art), daten);
    }
    @Override public long aufnahmeStand(String schluessel, String art) throws IOException { return stand(ordner.resolve(schluessel + "." + art)); }
    @Override public byte[] aufnahmeLaden(String schluessel, String art) throws IOException {
        Path p = ordner.resolve(schluessel + "." + art);
        return Files.exists(p) ? Files.readAllBytes(p) : null;
    }
    @Override public void aufnahmeLoeschen(String schluessel) throws IOException {
        for (String art : List.of("blick", "rueck")) Files.deleteIfExists(ordner.resolve(schluessel + "." + art));
    }
    @Override public List<String> aufnahmen() throws IOException {
        var liste = new ArrayList<String>();
        try (var dateien = Files.list(ordner)) {
            for (Path p : dateien.toList()) {
                String n = p.getFileName().toString();
                if (n.endsWith(".blick") && n.indexOf('@') > 0) liste.add(n.substring(0, n.length() - 6));
            }
        }
        Collections.sort(liste);
        return liste;
    }

    @Override public long szenenStand() throws IOException { return stand(ordner.resolve("szenen.txt")); }
    @Override public Map<String, Integer> szenen() throws IOException {
        var ergebnis = new HashMap<String, Integer>();
        Path datei = ordner.resolve("szenen.txt");
        if (!Files.exists(datei)) return ergebnis;
        for (String zeile : Files.readAllLines(datei, StandardCharsets.UTF_8)) {
            int g = zeile.indexOf('=');
            if (g > 0) ergebnis.put(zeile.substring(0, g).trim(), Integer.parseInt(zeile.substring(g + 1).trim()));
        }
        return ergebnis;
    }
    @Override public void szenenSetzen(Map<String, Integer> szenen) throws IOException {
        var text = new StringBuilder();
        szenen.entrySet().stream().sorted(Map.Entry.comparingByValue())
                .forEach(e -> text.append(e.getKey()).append('=').append(e.getValue()).append('\n'));
        schreiben(ordner.resolve("szenen.txt"), text.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Override public Path zumBacken(Path arbeitsordner) { return ordner; }
    @Override public void close() { }
}
