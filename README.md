# tsk Android

A small, native Android companion for the [tsk](https://git.sr.ht/~mapperr/tsk) plain-text task manager.

**Files, not a service.** One task is one `*.tsk.md` file. You can use the same task directory from Android or a Linux shell without converting formats.

## What it does

- Manage **multiple independent task sources** in one view.
  - **Local folders** selected with Android's system folder picker (Storage Access Framework / SAF). Folder permissions are persisted.
  - **Git repositories** cloned to app-private storage, with explicit pull/rebase/push via JGit. Multiple clones can be linked independently.
- View tasks from **all sources** together or filter down to one source.
- **Focus** view: overdue, today, tomorrow, next seven days, and `+next` tasks, with relative dates.
- **Tasks** and **Done** tabs, fast search, exact `+tag` and negative `-+tag` filters.
- Create, edit and delete tasks; edit tags, due date, completion, next status and multiline Markdown notes.
- Choose a date through an Android date picker.
- Per-repository **Sync** that stages/commits **only root-level `.tsk.md` files**, then pulls with rebase, then pushes.
- Git conflict workflow: view conflicted task files as raw text, edit conflict markers, continue or abort rebase.

The app **does not execute the shell script** and does not need Termux or root. Both the shell tool and this app independently implement the same minimal Markdown file format.

## Compatibility with tsk

Filename: `20261019094500123-AbCd.tsk.md`

```md
Fix certificate +work +next +due:2026-10-24

Markdown notes go here.
```

The title and `+tags` are on the **first line**; the remainder of the file is editable Markdown. Only `+done`, `+next`, `+due:YYYY-MM-DD` receive special treatment. All other tags are plain strings, including `+owner:...` or `+parent:...` from older collections.

The app does not rewrite existing task files merely because it reads them. Tapping **Done** or **Next** updates first-line metadata while keeping the Markdown body. Saving through the full editor serializes the title, tags, and notes into the canonical format.

For important task collections, **make a backup before your first edit**. SAF providers vary in how they implement overwrite/truncation; only Git-backed files are currently written through a local temporary file and rename.

Local sources are **not recursive**. Pick the directory that directly contains the `.tsk.md` files; a Git clone likewise uses tasks at repository root.

## Install / build

**Requirements:** JDK 17, Android SDK API 35, SDK Build Tools 35.0.0, internet for first Gradle/JGit download.

```sh
export ANDROID_HOME="$HOME/Android/Sdk"
# Ensure platforms;android-35 and build-tools;35.0.0 are installed.
./tests/run.sh
./build-apk.sh
```

The resulting file is `tsk-android-debug.apk`. Install it with Android's package installer or:

```sh
adb install -r tsk-android-debug.apk
```

You can alternatively open the project directly in Android Studio and choose **Build > Build APK(s)**.

The project includes a lightweight `gradlew` bootstrap script for Linux/macOS, which downloads Gradle 8.10.2 if it is not already installed. Unlike the official Gradle wrapper it does not carry a wrapper JAR. Run `gradle wrapper --gradle-version 8.10.2` yourself if you prefer the standard wrapper format.

On GitHub, the included optional Actions workflow can compile and upload a debug APK as a CI artifact; this does **not** require publishing the app on Google Play.

> **Build status:** The source package was created in an environment without Android SDK, Gradle or network access to the build repositories. The JVM task-format unit tests were run, but no Android APK was compiled, installed or tested in an emulator in that environment. The APK must be built using the steps above; do not treat the source package as a tested release binary.

## First run

1. Tap **Sources**.
2. Choose **+ Local folder** and grant access to the directory containing `.tsk.md` files; or choose **+ Git repository (HTTPS)**, provide the remote URL and clone it.
3. Add more folders/repositories at any time. The source selector defaults to **All sources**.
4. Tap **+ Task**, pick the destination source and add a title/tags/notes.
5. Tap **Done** or **Next** on a task; tap **Edit** for detailed changes.

All source configuration is stored locally on device. Removing a source disconnects it but **does not delete task files**. Git clones remain in app-private storage until app data is cleared or the app is uninstalled. Keep a Git remote or another backup of work you care about.

## Git setup and credentials

- **Only HTTPS Git remotes** are supported in this initial version. SSH remotes/keys, OAuth browser sign-in, Git LFS and submodules are not supported.
- HTTPS credentials are entered when cloning or syncing; tokens/passwords are **not saved**. Non-secret usernames and repository URLs are saved on device.
- Configure your **Git author name and email** under Sources > Git author identity before syncing locally changed tasks. This identity appears on commits.
- Cloned repositories are kept under app-private files. Direct browsing through Android's file picker is intentionally not assumed possible.
- The app does not periodically sync on its own. Sync is explicitly requested per repository.
- The remote should have an initialized branch with at least one commit and point to a Git service accessible over HTTPS.
- Git conflicts are never resolved automatically. Use **Sources > Conflicts** to edit full raw conflicted files and **Continue rebase** when ready, or **Abort rebase**. Never use an abort to discard work you still need.
- Pushing depends on the remote hosting service and your token permissions; a read-only token can clone/fetch but cannot push.

### Security and privacy

There is no analytics, tracking SDK, backend, or telemetry. The app requests `INTERNET` for Git only and uses Android's system folder permissions for local files. HTTPS URLs containing embedded passwords/userinfo are rejected. App backup is disabled to avoid accidentally exporting private Git clones.

For convenience, the standard Android `EditText` controls hold token text briefly while a request runs, but secrets are never written to SharedPreferences or Git remote configuration. Use restricted, revocable tokens.

## Project layout

```text
tsk-android/
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── res/values/styles.xml
│       └── java/io/mapperr/tsk/
│           ├── MainActivity.java   native UI
│           ├── TaskCodec.java     task format, focus, filters
│           ├── TaskStore.java     filesystem / SAF storage
│           ├── SourcePrefs.java   saved source configuration
│           └── GitBridge.java     HTTPS Git operations via JGit
├── tests/
│   ├── TaskCodecTest.java
│   └── run.sh
├── .github/workflows/android.yml
├── build-apk.sh
├── gradlew
└── justfile
```

Unit tests require just a JDK:

```sh
./tests/run.sh
# or
just test
```

## Scope

This is meant to be a **small mobile companion**, not a full Git client or a Jira clone. Deliberately out of scope are task graphs, teams, attachments, push notifications, scheduled background sync, multi-user permissions, remote code execution and rich Markdown rendering. There are no recurring task objects or hidden sidecar indexes.

License: see the upstream `tsk` repository for the original task tool; the Android project in this archive is provided as editable source.
