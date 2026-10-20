import io.mapperr.tsk.TaskCodec;
import io.mapperr.tsk.TaskCodec.Task;
import java.time.LocalDate;

public class TaskCodecTest {
    static int passed = 0;
    static void ok(boolean result, String why) {
        if (!result) throw new AssertionError(why);
        passed++;
    }
    public static void main(String[] args) {
        String raw = "Finish API +next +qilab +due:2026-10-22\n\n## Notes\nBody **raw**\n";
        Task t = TaskCodec.parse("20261019091400123-xY2a", "repo", raw);
        ok(t.title.equals("Finish API"), "title");
        ok(t.next() && t.tags.contains("qilab"), "tags");
        ok(TaskCodec.write(t).equals(raw), "raw round-trip");
        ok(TaskCodec.bucket(t, LocalDate.of(2026,10,19)) == TaskCodec.Bucket.WEEK, "week");
        ok(TaskCodec.dueLabel(t, LocalDate.of(2026,10,19)).equals("(3d)"), "relative date");
        ok(TaskCodec.matches(t,"+qilab api"), "search");
        ok(!TaskCodec.matches(t,"-+qilab"), "negative tag");
        ok(!TaskCodec.matches(t,"+qil"), "exact tag");
        t.setDone(true);
        ok(TaskCodec.bucket(t, LocalDate.now()) == TaskCodec.Bucket.DONE, "done");
        t.setDone(false); t.setDue("2026-10-17");
        ok(TaskCodec.dueLabel(t, LocalDate.of(2026,10,19)).equals("(2d late)"), "overdue label");
        ok(TaskCodec.bucket(t, LocalDate.of(2026,10,19)) == TaskCodec.Bucket.OVERDUE, "overdue");
        t.setDue("");
        ok(t.due().isEmpty(), "clear due");
        ok(t.tail.equals("\n## Notes\nBody **raw**\n"), "body preserved");
        ok(TaskCodec.shortId(t.id).equals("xY2a"), "short id");
        ok(TaskCodec.parseTags("+qilab +next").size() == 2, "tag parsing");
        try { TaskCodec.parseTags("+due:bogus"); throw new AssertionError("bad date accepted"); }
        catch (java.time.format.DateTimeParseException expected) { passed++; }
        System.out.println(passed + " TaskCodec checks passed");
    }
}
