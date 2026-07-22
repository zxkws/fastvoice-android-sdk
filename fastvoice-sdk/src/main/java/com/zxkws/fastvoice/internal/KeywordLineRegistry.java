package com.zxkws.fastvoice.internal;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Preserves every pronunciation line for a label in stable file order. */
final class KeywordLineRegistry {
    private static final class Entry {
        final String label;
        final String line;

        Entry(String label, String line) {
            this.label = label;
            this.line = line;
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    void load(BufferedReader reader) throws IOException {
        clear();
        String line;
        while ((line = reader.readLine()) != null) {
            String value = line.trim();
            int labelAt = value.lastIndexOf('@');
            if (labelAt < 0 || labelAt + 1 >= value.length()) continue;
            String label = value.substring(labelAt + 1).trim();
            if (label.isEmpty()) continue;
            entries.add(new Entry(label, value));
        }
    }

    String render(Collection<String> enabledLabels) {
        StringBuilder result = new StringBuilder();
        for (Entry entry : entries) {
            if (enabledLabels.contains(entry.label)) result.append(entry.line).append('\n');
        }
        return result.toString();
    }

    void clear() {
        entries.clear();
    }
}
