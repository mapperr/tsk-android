package io.mapperr.tsk;

import android.content.Context;
import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Storage abstraction. Local sources use Android SAF, Git sources use app-private files. */
public final class TaskStore {
    private final Context context;
    private final SourcePrefs sources;
    private final SecureRandom random = new SecureRandom();
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    public TaskStore(Context context, SourcePrefs sources) { this.context = context; this.sources = sources; }

    public static final class LoadResult {
        public final List<TaskCodec.Task> tasks = new ArrayList<>();
        public final List<String> errors = new ArrayList<>();
    }

    public File gitDir(SourcePrefs.Source source) {
        return new File(new File(context.getFilesDir(), "repos"), source.id);
    }

    public LoadResult load() {
        LoadResult result = new LoadResult();
        for (SourcePrefs.Source source : sources.all()) {
            try {
                if (source.isGit()) loadGit(source, result);
                else loadLocal(source, result);
            } catch (Exception e) {
                result.errors.add(source.name + ": " + cleanMessage(e));
            }
        }
        return result;
    }

    private void loadGit(SourcePrefs.Source src, LoadResult out) throws Exception {
        File root = gitDir(src);
        if (!new File(root,".git").isDirectory()) throw new IllegalStateException("Git clone missing");
        File[] files = root.listFiles();
        if (files == null) throw new IllegalStateException("Cannot read Git task directory");
        for (File file : files) {
            if (!file.isFile() || !file.getName().endsWith(TaskCodec.EXT)) continue;
            String id = file.getName().substring(0,file.getName().length()-TaskCodec.EXT.length());
            if (!TaskCodec.validId(id)) continue;
            try { out.tasks.add(TaskCodec.parse(id, src.id, read(new FileInputStream(file)))); }
            catch (Exception e) { out.errors.add(src.name + "/" + id + ": " + cleanMessage(e)); }
        }
    }

    private Uri tree(SourcePrefs.Source source) { return Uri.parse(source.location); }
    private Uri childUri(Uri tree, String docId) {
        return DocumentsContract.buildDocumentUriUsingTree(tree, docId);
    }
    private Uri childrenUri(Uri tree) {
        return DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
    }
    private String findId(SourcePrefs.Source source, String target) throws Exception {
        Uri tree = tree(source);
        try (Cursor cursor = context.getContentResolver().query(childrenUri(tree),
            new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                         DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cursor == null) throw new IllegalStateException("Folder is no longer accessible");
            while (cursor.moveToNext()) {
                if (target.equals(cursor.getString(1))) return cursor.getString(0);
            }
        }
        return null;
    }

    private void loadLocal(SourcePrefs.Source src, LoadResult out) throws Exception {
        Uri tree = tree(src);
        try (Cursor cursor = context.getContentResolver().query(childrenUri(tree),
            new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (cursor == null) throw new IllegalStateException("Folder access revoked");
            while (cursor.moveToNext()) {
                String docId = cursor.getString(0), name = cursor.getString(1);
                String mime = cursor.getString(2);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime) || !name.endsWith(TaskCodec.EXT)) continue;
                String id = name.substring(0, name.length()-TaskCodec.EXT.length());
                if (!TaskCodec.validId(id)) continue;
                try (InputStream in = context.getContentResolver().openInputStream(childUri(tree,docId))) {
                    if (in == null) throw new IllegalStateException("Cannot open file");
                    out.tasks.add(TaskCodec.parse(id, src.id, read(in)));
                } catch (Exception e) { out.errors.add(src.name + "/" + name + ": " + cleanMessage(e)); }
            }
        }
    }

    private SourcePrefs.Source requireSource(String id) {
        for (SourcePrefs.Source s : sources.all()) if (s.id.equals(id)) return s;
        throw new IllegalArgumentException("Source no longer exists");
    }

    public void save(TaskCodec.Task task) throws Exception {
        SourcePrefs.Source source = requireSource(task.sourceId);
        String raw = TaskCodec.write(task);
        String name = task.id + TaskCodec.EXT;
        if (source.isGit()) {
            File root = gitDir(source);
            if (!root.isDirectory()) throw new IllegalStateException("Repository missing");
            File file = new File(root, name);
            File tmp = File.createTempFile(".tsk-write-", ".tmp", root);
            try {
                try (OutputStream out = new FileOutputStream(tmp)) { out.write(raw.getBytes(StandardCharsets.UTF_8)); }
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } finally { tmp.delete(); }
        } else {
            Uri tree = tree(source);
            String docId = findId(source, name);
            Uri item;
            if (docId == null) {
                item = DocumentsContract.createDocument(context.getContentResolver(), tree,
                    "text/plain", name);
                if (item == null) throw new IllegalStateException("Folder is not writable");
            } else item = childUri(tree, docId);
            try (OutputStream out = context.getContentResolver().openOutputStream(item,"wt")) {
                if (out == null) throw new IllegalStateException("Folder is not writable");
                out.write(raw.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    public TaskCodec.Task create(String sourceId, String title, String tags, String body) throws Exception {
        SourcePrefs.Source source = requireSource(sourceId);
        String id;
        do {
            StringBuilder suffix = new StringBuilder();
            for (int i=0; i<4; i++) suffix.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            String stamp = new SimpleDateFormat("yyyyMMddHHmmssSSS",Locale.ROOT).format(new Date());
            id = TaskCodec.newId(stamp,suffix.toString());
        } while (source.isGit() ? new File(gitDir(source),id+TaskCodec.EXT).exists()
               : findId(source,id+TaskCodec.EXT)!=null);
        TaskCodec.Task task = new TaskCodec.Task(id,sourceId,title,body.isEmpty()?"": "\n"+body);
        task.tags = TaskCodec.parseTags(tags);
        save(task);
        return task;
    }

    public void delete(TaskCodec.Task task) throws Exception {
        SourcePrefs.Source source = requireSource(task.sourceId);
        String name = task.id + TaskCodec.EXT;
        if (source.isGit()) {
            File file = new File(gitDir(source),name);
            if (!file.isFile() || !file.delete()) throw new IllegalStateException("Cannot delete task");
        } else {
            String docId = findId(source,name);
            if (docId == null || !DocumentsContract.deleteDocument(context.getContentResolver(),
                    childUri(tree(source),docId))) throw new IllegalStateException("Cannot delete task");
        }
    }

    static String cleanMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause()!=null && t.getCause()!=t) t=t.getCause();
        String m=t.getMessage();
        return m==null || m.isEmpty()?t.getClass().getSimpleName():m;
    }
    private static String read(InputStream in) throws Exception {
        // Caller owns the stream; prevent local FileInputStream from leaking too.
        try (InputStream input = in; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int len;
            while ((len = input.read(buffer)) != -1) bytes.write(buffer,0,len);
            return bytes.toString(StandardCharsets.UTF_8.name());
        }
    }
}
