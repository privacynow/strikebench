package io.liftandshift.strikebench.db;

import java.util.ArrayList;
import java.util.List;

/** Small RFC-4180 row splitter shared by local CSV importers. */
final class Csv {
    private Csv() {}

    static String[] split(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"'); i++;
                } else quoted = !quoted;
            } else if (ch == ',' && !quoted) {
                out.add(cur.toString()); cur.setLength(0);
            } else cur.append(ch);
        }
        if (quoted) throw new IllegalArgumentException("unterminated quoted CSV field");
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }
}
