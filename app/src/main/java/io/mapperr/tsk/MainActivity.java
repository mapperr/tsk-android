package io.mapperr.tsk;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.*;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Native Android UI. All filesystem and Git I/O runs off the UI thread. */
public final class MainActivity extends Activity {
    private static final int PICK_FOLDER = 41;
    private static final int NAVY = Color.rgb(18,32,56);
    private static final int BLUE = Color.rgb(53,104,176);
    private static final int FG = Color.rgb(28,41,62);
    private static final int MUTED = Color.rgb(97,111,135);
    private static final int BG = Color.rgb(246,248,252);
    private static final int RED = Color.rgb(177,52,48);
    private static final int BORDER = Color.rgb(225,231,240);
    private static final String ALL = "*";
    private enum Screen { FOCUS, TASKS, DONE }
    private Screen screen = Screen.FOCUS;
    private String sourceFilter = ALL;
    private String query = "";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private SourcePrefs prefs;
    private TaskStore store;
    private final List<TaskCodec.Task> allTasks = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    private final Map<String,String> sourceNames = new HashMap<>();
    private LinearLayout main, listHolder, tabs;
    private Spinner sourceSpinner;
    private TextView status, summary;
    private EditText search;
    private Uri pendingFolder;
    private boolean busy=false;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = new SourcePrefs(this);
        store = new TaskStore(this, prefs);
        getWindow().setStatusBarColor(NAVY);
        getWindow().setNavigationBarColor(NAVY);
        buildUI();
        reload();
    }
    @Override protected void onDestroy() { worker.shutdown(); super.onDestroy(); }

    private int dp(float n) { return (int)(n * getResources().getDisplayMetrics().density + 0.5f); }
    private GradientDrawable rounded(int fill, int radius) {
        GradientDrawable d=new GradientDrawable(); d.setColor(fill); d.setCornerRadius(dp(radius)); return d;
    }
    private GradientDrawable bordered(int fill, int radius) {
        GradientDrawable d=rounded(fill,radius); d.setStroke(dp(1),BORDER); return d;
    }
    private LinearLayout vertical() {
        LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l;
    }
    private LinearLayout horizontal() {
        LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l;
    }
    private LinearLayout.LayoutParams lp(int w,int h) { return new LinearLayout.LayoutParams(w,h); }
    private void pad(View v,int a,int b,int c,int d) { v.setPadding(dp(a),dp(b),dp(c),dp(d)); }
    private TextView text(String value,int size,int color,boolean bold) {
        TextView t=new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return t;
    }
    private Button button(String label, boolean primary) {
        Button b=new Button(this); b.setText(label); b.setAllCaps(false); b.setTextSize(13);
        b.setMinHeight(0); b.setMinimumHeight(0);
        b.setBackground(rounded(primary?BLUE:Color.rgb(232,238,247),10));
        b.setTextColor(primary?Color.WHITE:FG); pad(b,12,8,12,8); return b;
    }
    private void buildUI() {
        main=vertical(); main.setBackgroundColor(BG);
        setContentView(main);

        LinearLayout header=horizontal(); pad(header,18,20,16,14); header.setBackgroundColor(NAVY);
        TextView title=text("tsk",28,Color.WHITE,true);
        header.addView(title,new LinearLayout.LayoutParams(0,dp(46),1));
        Button plus=button("+ Task",true); plus.setOnClickListener(v->editTask(null));
        header.addView(plus); main.addView(header);

        LinearLayout selectors=horizontal(); pad(selectors,14,12,14,8);
        sourceSpinner=new Spinner(this);
        selectors.addView(sourceSpinner,new LinearLayout.LayoutParams(0,dp(48),1));
        Button manage=button("Sources",false);
        manage.setOnClickListener(v->showSources());
        selectors.addView(manage); main.addView(selectors);

        search=new EditText(this); search.setSingleLine(true); search.setTextSize(15);
        search.setHint("Search title or tags, e.g. +work -+done");
        search.setBackground(bordered(Color.WHITE,12)); pad(search,15,11,15,11);
        LinearLayout.LayoutParams sp=lp(-1,dp(48)); sp.setMargins(dp(16),0,dp(16),dp(10));
        main.addView(search,sp);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
            public void onTextChanged(CharSequence s,int start,int before,int count) {
                query=s.toString(); render();
            }
            public void afterTextChanged(Editable s) {}
        });

        tabs=horizontal(); pad(tabs,14,0,14,10); main.addView(tabs);
        summary=text("",13,MUTED,false); pad(summary,18,0,14,7); main.addView(summary);
        status=text("",12,MUTED,false); pad(status,18,0,14,6); main.addView(status);
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true);
        listHolder=vertical(); pad(listHolder,14,4,14,22); scroll.addView(listHolder);
        main.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        rebuildSpinner(); renderTabs();
    }

    private void rebuildSpinner() {
        List<SourcePrefs.Source> sources=prefs.all();
        List<String> options=new ArrayList<>();
        options.add("All sources");
        int selected=0;
        for (int i=0;i<sources.size();i++) {
            options.add(sources.get(i).label());
            if (sourceFilter.equals(sources.get(i).id)) selected=i+1;
        }
        if (selected==0) sourceFilter=ALL;
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,options);
        sourceSpinner.setAdapter(adapter);
        sourceSpinner.setSelection(selected);
        sourceSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
            public void onItemSelected(android.widget.AdapterView<?> parent,View view,int position,long id) {
                sourceFilter=position==0?ALL:sources.get(position-1).id;
                render();
            }
        });
    }

    private void renderTabs() {
        tabs.removeAllViews();
        addTab("Focus",Screen.FOCUS);
        addTab("Tasks",Screen.TASKS);
        addTab("Done",Screen.DONE);
    }
    private void addTab(String label, Screen tab) {
        Button btn=button(label,screen==tab);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(42),1);
        p.setMargins(dp(3),0,dp(3),0); tabs.addView(btn,p);
        btn.setOnClickListener(v->{screen=tab;renderTabs();render();});
    }

    private void reload() {
        runJob("Reading tasks…",()->store.load(),result->{
            allTasks.clear(); allTasks.addAll(result.tasks);
            errors.clear(); errors.addAll(result.errors);
            sourceNames.clear(); for (SourcePrefs.Source s:prefs.all()) sourceNames.put(s.id,s.name);
            rebuildSpinner(); render();
        });
    }
    private <T> void runJob(String label, Callable<T> job,Consumer<T> success) {
        busy=true; status.setText(label);
        worker.execute(()->{
            try {
                T result=job.call();
                runOnUiThread(()->{
                    busy=false; status.setText(""); success.accept(result);
                });
            } catch(Exception e) {
                runOnUiThread(()->{
                    busy=false; status.setText("");
                    alert("Operation failed",TaskStore.cleanMessage(e));
                });
            }
        });
    }
    private void alert(String title,String body) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton("OK",null).show();
    }

    private void render() {
        if (listHolder==null) return;
        listHolder.removeAllViews();
        List<TaskCodec.Task> chosen=new ArrayList<>();
        for (TaskCodec.Task t:allTasks)
            if ((sourceFilter.equals(ALL)||t.sourceId.equals(sourceFilter)) && TaskCodec.matches(t,query))
                chosen.add(t);
        long open=chosen.stream().filter(t->!t.done()).count();
        summary.setText(open+" open · "+(chosen.size()-open)+" done · "+prefs.all().size()+" sources");
        if (!errors.isEmpty()) {
            TextView warning=text("Some sources could not be read (tap for details)",12,RED,true);
            pad(warning,10,9,10,9); warning.setBackground(rounded(Color.rgb(255,238,235),9));
            warning.setOnClickListener(v->alert("Storage problems",String.join("\n",errors)));
            listHolder.addView(warning,lp(-1,-2));
        }
        if (prefs.all().isEmpty()) {
            empty("Connect a folder or Git repository to start.","Add a source",v->showSources()); return;
        }
        LocalDate today=LocalDate.now();
        chosen.sort(Comparator.comparing((TaskCodec.Task t)->{
            LocalDate due=TaskCodec.date(t);
            return due==null?LocalDate.MAX:due;
        }).thenComparing(t->t.title.toLowerCase(Locale.ROOT)));
        if (screen==Screen.FOCUS) {
            int shown=0;
            for (TaskCodec.Bucket bucket : new TaskCodec.Bucket[]{TaskCodec.Bucket.OVERDUE,TaskCodec.Bucket.TODAY,
                    TaskCodec.Bucket.TOMORROW,TaskCodec.Bucket.WEEK,TaskCodec.Bucket.NEXT}) {
                List<TaskCodec.Task> group=new ArrayList<>();
                for (TaskCodec.Task t:chosen) if (TaskCodec.bucket(t,today)==bucket) group.add(t);
                if (group.isEmpty()) continue;
                shown+=group.size();
                String label=bucket==TaskCodec.Bucket.WEEK?"IN A WEEK":bucket.name();
                section(label,group,bucket,today);
            }
            if (shown==0) empty("Nothing requiring attention. Nice.","Show all tasks",v->{screen=Screen.TASKS;renderTabs();render();});
        } else {
            List<TaskCodec.Task> group=new ArrayList<>();
            for (TaskCodec.Task t:chosen) if (t.done()==(screen==Screen.DONE)) group.add(t);
            if (group.isEmpty()) empty("No tasks here.","Add task",v->editTask(null));
            else section(screen==Screen.DONE?"COMPLETED":"OPEN TASKS",group,null,today);
        }
    }

    private void section(String label,List<TaskCodec.Task> tasks,TaskCodec.Bucket bucket,LocalDate today) {
        TextView head=text(label+"  ·  "+tasks.size(),12,bucket==TaskCodec.Bucket.OVERDUE?RED:MUTED,true);
        pad(head,5,18,3,9); listHolder.addView(head);
        for (TaskCodec.Task t:tasks) listHolder.addView(taskCard(t,bucket,today));
    }
    private View taskCard(TaskCodec.Task task,TaskCodec.Bucket bucket,LocalDate today) {
        LinearLayout card=vertical(); card.setBackground(bordered(Color.WHITE,12)); pad(card,14,12,14,11);
        LinearLayout.LayoutParams cardLp=lp(-1,-2); cardLp.setMargins(0,0,0,dp(9)); card.setLayoutParams(cardLp);
        LinearLayout top=horizontal();
        TextView title=text(task.title,16,FG,true); title.setMaxLines(3);
        top.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        if (TaskCodec.date(task)!=null) {
            String due=TaskCodec.dueLabel(task,today);
            TextView dueView=text(due,12,TaskCodec.bucket(task,today)==TaskCodec.Bucket.OVERDUE?RED:BLUE,true);
            dueView.setBackground(rounded(Color.rgb(234,241,252),8)); pad(dueView,7,5,7,5);
            LinearLayout.LayoutParams p=lp(-2,-2);p.setMargins(dp(8),0,0,0); top.addView(dueView,p);
        }
        card.addView(top);
        TextView desc=text("["+TaskCodec.shortId(task.id)+"]  "+task.tagsText(),12,MUTED,false);
        pad(desc,0,4,0,6);card.addView(desc);
        if (sourceFilter.equals(ALL)) {
            TextView src=text("◦ "+sourceNames.getOrDefault(task.sourceId,"Unknown source"),11,BLUE,false);
            card.addView(src);
        }
        LinearLayout actions=horizontal(); pad(actions,0,7,0,0);
        Button done=button(task.done()?"Reopen":"Done",false);
        done.setOnClickListener(v->{ TaskCodec.Task updated=task.copy(); updated.setDone(!updated.done());
            runJob("Saving…",()->{store.save(updated);return true;},ok->reload()); });
        actions.addView(done);
        Button next=button(task.next()?"★ Next":"☆ Next",false);
        LinearLayout.LayoutParams np=lp(-2,-2);np.setMargins(dp(9),0,0,0);
        next.setOnClickListener(v->{ TaskCodec.Task updated=task.copy();updated.setNext(!updated.next());
            runJob("Saving…",()->{store.save(updated);return true;},ok->reload()); });
        actions.addView(next,np);
        Button edit=button("Edit",false);
        LinearLayout.LayoutParams ep=lp(-2,-2);ep.setMargins(dp(9),0,0,0);
        edit.setOnClickListener(v->editTask(task));actions.addView(edit,ep);
        card.addView(actions);
        card.setOnClickListener(v->editTask(task));
        return card;
    }
    private void empty(String message,String action,View.OnClickListener click) {
        LinearLayout panel=vertical();panel.setGravity(Gravity.CENTER);pad(panel,22,48,22,25);
        TextView label=text(message,17,MUTED,false);label.setGravity(Gravity.CENTER);panel.addView(label);
        Button b=button(action,true);LinearLayout.LayoutParams p=lp(-2,-2);p.topMargin=dp(16);
        panel.addView(b,p);b.setOnClickListener(click);listHolder.addView(panel);
    }

    private EditText field(String hint,String value,boolean multi) {
        EditText e=new EditText(this);e.setHint(hint);e.setText(value);
        e.setTextSize(15);if (!multi) e.setSingleLine(true);
        else {e.setGravity(Gravity.TOP);e.setMinLines(4);e.setMaxLines(12);}
        return e;
    }
    private void formField(LinearLayout form,String label,View input) {
        TextView text=text(label,12,MUTED,true);pad(text,0,12,0,0);
        form.addView(text);form.addView(input,lp(-1,-2));
    }
    private ScrollView formWrap(LinearLayout content) {
        ScrollView scroller=new ScrollView(this);pad(content,16,6,16,10);scroller.addView(content);
        return scroller;
    }

    private void editTask(TaskCodec.Task existing) {
        List<SourcePrefs.Source> sources=prefs.all();
        if (sources.isEmpty()) {alert("No task source","Connect a folder or Git repository first.");return;}
        LinearLayout form=vertical();
        Spinner location=new Spinner(this);
        List<String> names=new ArrayList<>();int initially=0;
        for (int i=0;i<sources.size();i++) {
            names.add(sources.get(i).label());
            if (existing!=null && sources.get(i).id.equals(existing.sourceId)) initially=i;
            else if (existing==null && sources.get(i).id.equals(sourceFilter)) initially=i;
        }
        location.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names));
        location.setSelection(initially);location.setEnabled(existing==null);
        formField(form,"SOURCE",location);
        EditText title=field("What needs doing?",existing==null?"":existing.title,false);
        formField(form,"TITLE",title);
        EditText tags=field("+work +next",existing==null?"":existing.tagsText(),false);
        formField(form,"TAGS",tags);
        EditText due=field("YYYY-MM-DD (optional)",existing==null?"":existing.due(),false);
        LinearLayout row=horizontal();row.addView(due,new LinearLayout.LayoutParams(0,-2,1));
        Button pick=button("Calendar",false); row.addView(pick);
        pick.setOnClickListener(v->{
            LocalDate start;
            try {start=LocalDate.parse(due.getText().toString().trim());}
            catch(Exception ignored){start=LocalDate.now();}
            new DatePickerDialog(this,(picker,y,m,d)->due.setText(LocalDate.of(y,m+1,d).toString()),
                start.getYear(),start.getMonthValue()-1,start.getDayOfMonth()).show();
        });
        formField(form,"DUE DATE",row);
        String currentBody=existing==null?"":existing.tail;
        if (currentBody.startsWith("\n")) currentBody=currentBody.substring(1);
        EditText body=field("Markdown notes (optional)",currentBody,true);
        formField(form,"NOTES (MARKDOWN)",body);
        AlertDialog.Builder dialog=new AlertDialog.Builder(this)
            .setTitle(existing==null?"New task":"Edit task")
            .setView(formWrap(form))
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Save",null);
        if (existing!=null) dialog.setNeutralButton("Delete",(d,which)->
            new AlertDialog.Builder(this).setTitle("Delete task?")
                .setMessage("Delete '"+existing.title+"'? This cannot be undone outside Git history.")
                .setNegativeButton("Cancel",null)
                .setPositiveButton("Delete",(dd,w)->
                    runJob("Deleting…",()->{store.delete(existing);return true;},ok->reload())).show());
        AlertDialog actual=dialog.create();actual.setOnShowListener(ignored->{
            actual.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                String enteredTitle=title.getText().toString().trim();
                String enteredTags=tags.getText().toString().trim();
                String enteredDue=due.getText().toString().trim();
                String enteredBody=body.getText().toString();
                if (enteredTitle.isEmpty()) {title.setError("Title required");return;}
                try {
                    java.util.LinkedHashSet<String> parsed=TaskCodec.parseTags(enteredTags);
                    if (!enteredDue.isEmpty()) LocalDate.parse(enteredDue);
                    parsed.removeIf(t->t.startsWith("due:"));
                    if (!enteredDue.isEmpty()) parsed.add("due:"+enteredDue);
                    if (existing!=null) {
                        TaskCodec.Task changed=existing.copy();
                        changed.title=enteredTitle; changed.tags=parsed;
                        changed.tail=enteredBody.isEmpty()?"":"\n"+enteredBody;
                        TaskCodec.write(changed);
                        actual.dismiss();
                        runJob("Saving…",()->{store.save(changed);return true;},ok->reload());
                    } else {
                        String sourceId=sources.get(location.getSelectedItemPosition()).id;
                        actual.dismiss();
                        runJob("Creating…",()->store.create(sourceId,enteredTitle,joinTags(parsed),enteredBody),ok->reload());
                    }
                } catch (Exception ex) {alert("Invalid task",TaskStore.cleanMessage(ex));}
            });
        });
        actual.show();
    }
    private String joinTags(java.util.Collection<String> tags) {
        List<String> result=new ArrayList<>();
        for(String tag:tags) result.add("+"+tag);
        return String.join(" ",result);
    }

    private void showSources() {
        LinearLayout content=vertical();
        Button local=button("+ Local folder",true);content.addView(local,lp(-1,dp(48)));
        Button git=button("+ Git repository (HTTPS)",false);
        LinearLayout.LayoutParams gp=lp(-1,dp(48));gp.topMargin=dp(9);content.addView(git,gp);
        Button identity=button("Git author identity",false);
        LinearLayout.LayoutParams ip=lp(-1,dp(46));ip.topMargin=dp(9);content.addView(identity,ip);
        AlertDialog modal=new AlertDialog.Builder(this).setTitle("Task sources")
            .setView(formWrap(content)).setPositiveButton("Close",null).create();
        local.setOnClickListener(v->{modal.dismiss();chooseFolder();});
        git.setOnClickListener(v->{modal.dismiss();addGit();});
        identity.setOnClickListener(v->{modal.dismiss();editIdentity();});
        for(SourcePrefs.Source s:prefs.all()) {
            TextView label=text(s.label(),15,FG,true);pad(label,0,21,0,5);content.addView(label);
            TextView detail=text(s.isGit()?s.location:"Android document folder",12,MUTED,false);
            detail.setMaxLines(2);content.addView(detail);
            LinearLayout actions=horizontal();pad(actions,0,6,0,0);
            if (s.isGit()) {
                Button sync=button("Sync",false);actions.addView(sync);
                sync.setOnClickListener(v->{modal.dismiss();syncGit(s);});
                Button resolve=button("Conflicts",false);
                LinearLayout.LayoutParams rp=lp(-2,-2);rp.leftMargin=dp(8); actions.addView(resolve,rp);
                resolve.setOnClickListener(v->{modal.dismiss();showConflicts(s);});
                Button abort=button("Abort rebase",false);
                LinearLayout.LayoutParams ap=lp(-2,-2);ap.leftMargin=dp(8); actions.addView(abort,ap);
                abort.setOnClickListener(v->{modal.dismiss();
                    new AlertDialog.Builder(this).setTitle("Abort unfinished Git rebase?")
                        .setMessage("Uncommitted conflict resolution may be lost. Local commits will remain.")
                        .setNegativeButton("Cancel",null)
                        .setPositiveButton("Abort",(dd,which)->runJob("Aborting rebase…",
                            ()->GitBridge.abortRebase(store.gitDir(s)),msg->{alert("Git",msg);reload();})).show();
                });
            }
            Button unlink=button("Remove",false);
            LinearLayout.LayoutParams up=lp(-2,-2);up.leftMargin=dp(8);actions.addView(unlink,up);
            unlink.setOnClickListener(v->{modal.dismiss();
                new AlertDialog.Builder(this).setTitle("Remove source?")
                    .setMessage("The task files will not be deleted. For Git sources, the app's private clone remains on this device until app data is cleared.")
                    .setNegativeButton("Cancel",null)
                    .setPositiveButton("Remove",(dd,which)->{prefs.remove(s.id);reload();}).show();
            });
            content.addView(actions);
        }
        modal.show();
    }

    private void chooseFolder() {
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent,PICK_FOLDER);
    }
    @Override protected void onActivityResult(int request,int code,Intent data) {
        super.onActivityResult(request,code,data);
        if (request!=PICK_FOLDER || code!=RESULT_OK || data==null || data.getData()==null) return;
        Uri uri=data.getData();
        int grant=data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (grant==0) {alert("Folder access","No storage permission granted");return;}
        try {getContentResolver().takePersistableUriPermission(uri,grant);} catch(Exception e) {
            alert("Folder access","Cannot persist permission: "+TaskStore.cleanMessage(e));return;
        }
        EditText name=field("Source name","Local tasks",false);
        new AlertDialog.Builder(this).setTitle("Name this folder")
            .setView(name).setNegativeButton("Cancel",null)
            .setPositiveButton("Add",(dialog,which)->{
                String n=name.getText().toString().trim();
                if (n.isEmpty()) n="Local tasks";
                prefs.save(new SourcePrefs.Source(SourcePrefs.newSourceId(),n,
                    SourcePrefs.LOCAL,uri.toString(),""));reload();
            }).show();
    }

    private void addGit() {
        LinearLayout form=vertical();
        EditText name=field("Project name","",false);
        EditText url=field("https://git.example.com/user/tasks.git","",false);
        EditText username=field("Username (optional)","",false);
        EditText secret=field("Access token / password (not saved)","",false);
        secret.setInputType(129);
        formField(form,"NAME",name);
        formField(form,"HTTPS GIT URL",url);
        formField(form,"USERNAME",username);
        formField(form,"TOKEN (OPTIONAL)",secret);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Clone task repository")
            .setView(formWrap(form)).setNegativeButton("Cancel",null)
            .setPositiveButton("Clone",null).create();
        dialog.setOnShowListener(ignore->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String remote=url.getText().toString().trim();
            try {GitBridge.validateUrl(remote);}catch(Exception e){url.setError(TaskStore.cleanMessage(e));return;}
            String display=name.getText().toString().trim(); if(display.isEmpty()) display="Git tasks";
            String user=username.getText().toString().trim();
            GitBridge.Login login=new GitBridge.Login(user,secret.getText().toString().toCharArray());
            String id=SourcePrefs.newSourceId();
            SourcePrefs.Source source=new SourcePrefs.Source(id,display,SourcePrefs.GIT,remote,user);
            dialog.dismiss();
            runJob("Cloning "+display+"…",()->{
                try {
                    GitBridge.cloneRepository(remote,store.gitDir(source),login);
                    prefs.save(source);
                    return true;
                } finally {login.clear();}
            },ok->reload());
        }));
        dialog.show();
    }

    private void syncGit(SourcePrefs.Source src) {
        LinearLayout form=vertical();
        TextView remote=text(src.location,13,MUTED,false);form.addView(remote);
        EditText username=field("Username",src.username,false);
        EditText secret=field("Access token / password (not saved)","",false);
        secret.setInputType(129);
        formField(form,"USERNAME",username);
        formField(form,"TOKEN (LEAVE EMPTY FOR PUBLIC REMOTES)",secret);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Sync "+src.name)
            .setView(formWrap(form)).setNegativeButton("Cancel",null)
            .setPositiveButton("Sync",(d,which)->{
                String user=username.getText().toString().trim();
                src.username=user; prefs.save(src);
                GitBridge.Login login=new GitBridge.Login(user,secret.getText().toString().toCharArray());
                runJob("Syncing "+src.name+"…",()->{
                    try {return GitBridge.sync(store.gitDir(src),login,prefs.gitName(),prefs.gitEmail());}
                    finally {login.clear();}
                },message->{reload();alert("Git sync",message);});
            }).create();
        dialog.show();
    }


    private void showConflicts(SourcePrefs.Source source) {
        runJob("Checking Git conflicts…",()->GitBridge.conflictingTasks(store.gitDir(source)),files->{
            if (files.isEmpty()) {
                alert("Git", "No conflicted task files. If a rebase is in progress, select Continue rebase.");
                new AlertDialog.Builder(this).setTitle("Continue rebase?")
                    .setMessage("Only continue when all Git conflicts are resolved.")
                    .setNegativeButton("Cancel",null)
                    .setPositiveButton("Continue",(d,w)->continueGitRebase(source)).show();
                return;
            }
            String[] options=new String[files.size()+1];
            for (int i=0;i<files.size();i++) options[i]=files.get(i);
            options[files.size()]="Continue rebase (after editing every conflict)";
            new AlertDialog.Builder(this).setTitle("Conflicted task files")
                .setItems(options,(d,which)->{
                    if (which==files.size()) continueGitRebase(source);
                    else editConflictFile(source,files.get(which));
                }).setNegativeButton("Close",null).show();
        });
    }
    private void editConflictFile(SourcePrefs.Source src,String filename) {
        runJob("Opening conflict…",()->{
            java.nio.file.Path path=new java.io.File(store.gitDir(src),filename).toPath();
            return new String(java.nio.file.Files.readAllBytes(path),java.nio.charset.StandardCharsets.UTF_8);
        },raw->{
            EditText editor=field("Edit raw conflict markers",raw,true);
            editor.setMinLines(12);
            editor.setTypeface(Typeface.MONOSPACE);
            AlertDialog dialog=new AlertDialog.Builder(this)
                .setTitle("Resolve: "+filename).setView(editor)
                .setNegativeButton("Cancel",null).setPositiveButton("Save",(d,which)->{
                    String text=editor.getText().toString();
                    runJob("Saving resolution…",()->{
                        java.nio.file.Path file=new java.io.File(store.gitDir(src),filename).toPath();
                        java.nio.file.Path tmp=java.nio.file.Files.createTempFile(file.getParent(),".tsk-resolve-",".tmp");
                        try {
                            java.nio.file.Files.write(tmp,text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            java.nio.file.Files.move(tmp,file,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        } finally {java.nio.file.Files.deleteIfExists(tmp);}
                        return true;
                    },ok->showConflicts(src));
                }).create();
            dialog.show();
        });
    }
    private void continueGitRebase(SourcePrefs.Source src) {
        runJob("Continuing rebase…",()->GitBridge.continueRebase(store.gitDir(src)),message->{
            alert("Git",message);reload();
        });
    }

    private void editIdentity() {
        LinearLayout form=vertical();
        EditText name=field("Full name",prefs.gitName(),false);
        EditText email=field("Email",prefs.gitEmail(),false);
        formField(form,"GIT AUTHOR NAME",name);formField(form,"GIT AUTHOR EMAIL",email);
        new AlertDialog.Builder(this).setTitle("Git commit identity")
            .setView(formWrap(form)).setNegativeButton("Cancel",null)
            .setPositiveButton("Save",(d,which)->prefs.setGitIdentity(name.getText().toString(),email.getText().toString())).show();
    }
}
