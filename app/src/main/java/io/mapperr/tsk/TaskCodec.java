package io.mapperr.tsk;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Exact interoperable first-line format for tsk 2.1.x, independent of Android. */
public final class TaskCodec {
    private TaskCodec() {}
    public static final String EXT = ".tsk.md";
    private static final Pattern TAG = Pattern.compile("[A-Za-z0-9_-]+(:[A-Za-z0-9_-]+)?");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]*");

    public static final class Task {
        public String id;
        public String sourceId;
        public String title;
        /** Text after first line break. Kept intact when metadata changes. */
        public String tail;
        public LinkedHashSet<String> tags = new LinkedHashSet<>();
        public Task(String id, String sourceId, String title, String tail) {
            this.id = id; this.sourceId = sourceId; this.title = title; this.tail = tail;
        }
        public boolean done() { return tags.contains("done"); }
        public boolean next() { return tags.contains("next"); }
        public String due() {
            for (String tag: tags) if (tag.startsWith("due:")) return tag.substring(4);
            return "";
        }
        public void setDone(boolean value) { if (value) tags.add("done"); else tags.remove("done"); }
        public void setNext(boolean value) { if (value) tags.add("next"); else tags.remove("next"); }
        public void setDue(String date) {
            tags.removeIf(t -> t.startsWith("due:"));
            if (!date.isEmpty()) { LocalDate.parse(date); tags.add("due:" + date); }
        }
        public String tagsText() {
            List<String> list = new ArrayList<>();
            for (String t : tags) list.add("+" + t);
            return String.join(" ", list);
        }
        public Task copy() {
            Task t = new Task(id, sourceId, title, tail);
            t.tags.addAll(tags);
            return t;
        }
    }

    public static boolean validId(String id) { return id != null && ID.matcher(id).matches(); }
    public static boolean validTag(String tag) { return tag != null && TAG.matcher(tag).matches(); }

    public static Task parse(String id, String sourceId, String raw) {
        if (!validId(id)) throw new IllegalArgumentException("Invalid task ID: " + id);
        int split = raw.indexOf('\n');
        String first = split < 0 ? raw : raw.substring(0, split);
        if (first.endsWith("\r")) first = first.substring(0, first.length()-1);
        String tail = split < 0 ? "" : raw.substring(split+1);
        StringBuilder title = new StringBuilder();
        Task task = new Task(id, sourceId, "", tail);
        for (String part : first.trim().split("\\s+")) {
            if (part.isEmpty()) continue;
            if (part.startsWith("+") && validTag(part.substring(1))) {
                task.tags.add(part.substring(1));
            } else {
                if (title.length() > 0) title.append(' ');
                title.append(part);
            }
        }
        task.title = title.toString();
        return task;
    }

    public static LinkedHashSet<String> parseTags(String s) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (s == null || s.trim().isEmpty()) return result;
        for (String token : s.trim().split("\\s+")) {
            String value = token.startsWith("+") ? token.substring(1) : token;
            if (!validTag(value)) throw new IllegalArgumentException("Invalid tag: " + token);
            result.add(value);
        }
        int dues = 0;
        for (String tag : result) {
            if (tag.startsWith("due:")) {
                LocalDate.parse(tag.substring(4));
                dues++;
            }
        }
        if (dues > 1) throw new IllegalArgumentException("Only one due date is allowed");
        return result;
    }

    public static String write(Task t) {
        if (t.title == null || t.title.trim().isEmpty()) throw new IllegalArgumentException("Title required");
        if (t.title.indexOf('\n') >= 0 || t.title.indexOf('\r') >= 0) throw new IllegalArgumentException("Title must be one line");
        StringBuilder out = new StringBuilder(t.title.trim());
        for (String tag : t.tags) {
            if (!validTag(tag)) throw new IllegalArgumentException("Invalid tag " + tag);
            out.append(" +").append(tag);
        }
        out.append('\n').append(t.tail == null ? "" : t.tail);
        return out.toString();
    }

    public static String shortId(String id) {
        int pos = id.lastIndexOf('-');
        return pos < 0 ? id : id.substring(pos + 1);
    }

    public enum Bucket { OVERDUE, TODAY, TOMORROW, WEEK, NEXT, LATER, DONE }
    public static Bucket bucket(Task t, LocalDate today) {
        if (t.done()) return Bucket.DONE;
        LocalDate d = date(t);
        if (d != null) {
            long days = ChronoUnit.DAYS.between(today, d);
            if (days < 0) return Bucket.OVERDUE;
            if (days == 0) return Bucket.TODAY;
            if (days == 1) return Bucket.TOMORROW;
            if (days <= 7) return Bucket.WEEK;
        }
        return t.next() ? Bucket.NEXT : Bucket.LATER;
    }

    public static LocalDate date(Task t) {
        try { return t.due().isEmpty() ? null : LocalDate.parse(t.due()); }
        catch (RuntimeException ignored) { return null; }
    }

    public static String dueLabel(Task t, LocalDate today) {
        LocalDate d = date(t);
        if (d == null) return t.due().isEmpty() ? "" : "Invalid due date: " + t.due();
        long days = ChronoUnit.DAYS.between(today, d);
        if (days < 0) return "(" + (-days) + "d late)";
        if (days == 0) return "Today";
        if (days == 1) return "Tomorrow";
        return "(" + days + "d)";
    }

    /** Search is literal/case-insensitive, +tag exact and -+tag negated, like tsk l. */
    public static boolean matches(Task t, String query) {
        if (query == null || query.trim().isEmpty()) return true;
        String corpus = (t.title + " " + t.id + " " + t.tagsText()).toLowerCase(Locale.ROOT);
        for (String word : query.trim().split("\\s+")) {
            boolean neg = word.startsWith("-");
            String term = neg ? word.substring(1) : word;
            if (term.isEmpty()) continue;
            boolean yes;
            if (term.startsWith("+")) yes = t.tags.contains(term.substring(1));
            else yes = corpus.contains(term.toLowerCase(Locale.ROOT));
            if (neg == yes) return false;
        }
        return true;
    }

    public static String newId(String now, String suffix) {
        String id = now + "-" + suffix;
        if (!validId(id)) throw new IllegalArgumentException("Bad ID");
        return id;
    }
}
