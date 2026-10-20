package io.mapperr.tsk;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.RebaseCommand;
import org.eclipse.jgit.api.RebaseResult;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Git over HTTPS (JGit). No shell execution or third-party Git application. */
public final class GitBridge {
    private GitBridge() {}

    public static void validateUrl(String remote) {
        // Never permit cleartext HTTP or inline URL credentials (which Git saves in .git/config).
        try {
            java.net.URI uri = new java.net.URI(remote);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null || uri.getRawUserInfo()!=null)
                throw new IllegalArgumentException("Use an HTTPS Git URL without embedded credentials");
        } catch (java.net.URISyntaxException e) { throw new IllegalArgumentException("Invalid Git URL"); }
    }

    public static final class Login {
        public final String user;
        private final char[] secret;
        public Login(String user, char[] secret) {
            this.user=user==null?"":user;
            this.secret=secret==null?new char[0]:secret.clone();
        }
        private CredentialsProvider provider() {
            if (secret.length==0) return null;
            return new UsernamePasswordCredentialsProvider(user.isEmpty()?"oauth2":user,secret);
        }
        public void clear() { Arrays.fill(secret,'\0'); }
    }

    public static void cloneRepository(String remote, File dir, Login login) throws Exception {
        validateUrl(remote);
        File parent = dir.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IllegalStateException("Cannot create repo directory");
        if (dir.exists()) throw new IllegalStateException("Repository folder already exists");
        org.eclipse.jgit.api.CloneCommand cmd = Git.cloneRepository().setURI(remote).setDirectory(dir);
        CredentialsProvider cp = login.provider();
        if (cp!=null) cmd.setCredentialsProvider(cp);
        boolean success = false;
        try (Git ignored = cmd.call()) { success = true; }
        finally { if (!success) deleteFailedClone(dir); }
    }

    private static void deleteFailedClone(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteFailedClone(child);
        file.delete();
    }

    public static String sync(File dir, Login login, String name, String email) throws Exception {
        try (Git git = Git.open(dir)) {
            Repository repo = git.getRepository();
            if (repo.getRepositoryState()!=RepositoryState.SAFE)
                throw new IllegalStateException("Repository has an unfinished Git operation. Resolve or abort it first.");
            String branch = repo.getBranch();
            if (branch==null || branch.isEmpty() || branch.equals("HEAD"))
                throw new IllegalStateException("Detached HEAD is not supported");
            // Never stage README, configuration, or other non-task files.
            Status status = git.status().call();
            Set<String> changed = new HashSet<>();
            changed.addAll(status.getAdded());
            changed.addAll(status.getChanged());
            changed.addAll(status.getModified());
            changed.addAll(status.getUntracked());
            for (String path : changed) if (isRootTask(path)) git.add().addFilepattern(path).call();
            Set<String> deleted = new HashSet<>();
            deleted.addAll(status.getMissing());
            for (String path : deleted) if (isRootTask(path)) git.rm().addFilepattern(path).call();

            Status afterAdd = git.status().call();
            Set<String> stagedPaths = new HashSet<>();
            stagedPaths.addAll(afterAdd.getAdded());
            stagedPaths.addAll(afterAdd.getChanged());
            stagedPaths.addAll(afterAdd.getRemoved());
            for (String path : stagedPaths) if (!isRootTask(path))
                throw new IllegalStateException("Non-task Git changes are already staged: " + path
                    + ". Unstage them with a Git client before syncing.");
            boolean staged = !stagedPaths.isEmpty();
            if (staged) {
                if (name.trim().isEmpty() || email.trim().isEmpty())
                    throw new IllegalStateException("Set Git author name and email in Settings before syncing edited tasks");
                git.commit().setAuthor(name.trim(), email.trim()).setCommitter(name.trim(),email.trim())
                    .setMessage("tsk Android: update tasks").call();
            }

            org.eclipse.jgit.api.PullCommand pull = git.pull().setRemote("origin")
                .setRemoteBranchName(branch).setRebase(true);
            CredentialsProvider cp = login.provider();
            if (cp != null) pull.setCredentialsProvider(cp);
            PullResult result = pull.call();
            if (!result.isSuccessful())
                throw new IllegalStateException("Pull/rebase could not finish. Review conflicts before syncing again.");
            org.eclipse.jgit.api.PushCommand push = git.push().setRemote("origin")
                .setRefSpecs(new RefSpec("refs/heads/"+branch+":refs/heads/"+branch));
            if (cp != null) push.setCredentialsProvider(cp);
            for (PushResult p : push.call()) {
                for (RemoteRefUpdate update : p.getRemoteUpdates()) {
                    RemoteRefUpdate.Status statusCode = update.getStatus();
                    if (statusCode!=RemoteRefUpdate.Status.OK && statusCode!=RemoteRefUpdate.Status.UP_TO_DATE)
                        throw new IllegalStateException("Push refused ("+statusCode+"): "+update.getMessage());
                }
            }
            return "Synced branch " + branch + (staged ? "; committed local task changes" : "");
        }
    }

    public static String abortRebase(File dir) throws Exception {
        try (Git git=Git.open(dir)) {
            RepositoryState st=git.getRepository().getRepositoryState();
            if (st==RepositoryState.SAFE) return "No rebase is in progress";
            RebaseResult result=git.rebase().setOperation(RebaseCommand.Operation.ABORT).call();
            return "Rebase aborted: "+result.getStatus();
        }
    }


    /** A safe, explicit conflict workflow: edit the whole raw file and resume rebase. */
    public static java.util.List<String> conflictingTasks(File dir) throws Exception {
        try (Git git = Git.open(dir)) {
            java.util.List<String> files = new java.util.ArrayList<>();
            for (String name : git.status().call().getConflicting()) {
                if (isRootTask(name)) files.add(name);
                else throw new IllegalStateException("Non-task file conflict: " + name
                    + ". This requires a normal Git client.");
            }
            java.util.Collections.sort(files);
            return files;
        }
    }

    public static String continueRebase(File dir) throws Exception {
        try (Git git = Git.open(dir)) {
            if (git.getRepository().getRepositoryState() == RepositoryState.SAFE)
                return "No rebase is in progress";
            Set<String> conflicts = git.status().call().getConflicting();
            for (String path : conflicts) {
                if (!isRootTask(path)) throw new IllegalStateException("Non-task Git conflict: " + path);
                byte[] bytes = java.nio.file.Files.readAllBytes(new File(dir, path).toPath());
                String raw = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                if (raw.contains("<<<<<<<") || raw.contains("=======") || raw.contains(">>>>>>>"))
                    throw new IllegalStateException("Resolve all conflict markers in " + path + " first");
                git.add().addFilepattern(path).call();
            }
            RebaseResult result = git.rebase().setOperation(RebaseCommand.Operation.CONTINUE).call();
            if (!result.getStatus().isSuccessful())
                throw new IllegalStateException("Rebase needs further resolution: " + result.getStatus());
            return "Rebase continued (" + result.getStatus() + "). Now run Sync to push.";
        }
    }

    private static boolean isRootTask(String path) {
        return path.endsWith(TaskCodec.EXT) && path.indexOf('/')<0 && path.indexOf('\\')<0;
    }
}
