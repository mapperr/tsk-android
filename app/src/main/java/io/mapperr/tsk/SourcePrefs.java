package io.mapperr.tsk;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Stores only source metadata, tree grants and non-secret account names. */
public final class SourcePrefs {
    public static final String LOCAL = "local";
    public static final String GIT = "git";
    private final SharedPreferences prefs;

    public static final class Source {
        public String id, name, type, location, username;
        public Source(String id, String name, String type, String location, String username) {
            this.id=id; this.name=name; this.type=type; this.location=location; this.username=username;
        }
        public boolean isGit() { return type.equals(GIT); }
        public String label() { return name + (isGit() ? " · Git" : " · Folder"); }
    }

    public SourcePrefs(Context context) {
        prefs = context.getSharedPreferences("tsk_sources_v1", Context.MODE_PRIVATE);
    }
    public synchronized List<Source> all() {
        ArrayList<Source> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString("sources", "[]"));
            for (int i=0; i<array.length(); i++) {
                JSONObject j = array.getJSONObject(i);
                result.add(new Source(j.getString("id"), j.getString("name"),
                    j.getString("type"), j.getString("location"), j.optString("username", "")));
            }
        } catch (Exception e) { throw new IllegalStateException("Invalid saved source configuration", e); }
        return result;
    }
    public synchronized void save(Source source) {
        List<Source> list = all();
        boolean found = false;
        for (int i=0; i<list.size(); i++) if (list.get(i).id.equals(source.id)) {
            list.set(i, source); found = true; break;
        }
        if (!found) list.add(source);
        persist(list);
    }
    public synchronized void remove(String id) {
        List<Source> list = all();
        list.removeIf(s -> s.id.equals(id));
        persist(list);
    }
    private void persist(List<Source> sources) {
        JSONArray array = new JSONArray();
        for (Source s : sources) {
            JSONObject j = new JSONObject();
            try {
                j.put("id",s.id); j.put("name",s.name); j.put("type",s.type);
                j.put("location",s.location); j.put("username",s.username);
            } catch (Exception e) { throw new IllegalStateException(e); }
            array.put(j);
        }
        if (!prefs.edit().putString("sources", array.toString()).commit())
            throw new IllegalStateException("Failed to save source configuration");
    }
    public static String newSourceId() { return UUID.randomUUID().toString(); }
    public String gitName() { return prefs.getString("git_name", ""); }
    public String gitEmail() { return prefs.getString("git_email", ""); }
    public void setGitIdentity(String name, String email) {
        if (!prefs.edit().putString("git_name",name.trim()).putString("git_email",email.trim()).commit())
            throw new IllegalStateException("Failed to save Git identity");
    }
}
