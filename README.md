# umeos

Native Kotlin + Jetpack Compose client for a Memos server. The app targets Android 16 (compile/target SDK 36), supports Android 8.0+ (min SDK 26), and uses a lightweight Gruvbox Markdown editor adapted from the supplied `mark-main.zip` project.

## App features

- Connect to a Memos server with a Personal Access Token (PAT); verify it using `GET /api/auth/me`.
- Store the server URL and PAT in AndroidX encrypted preferences.
- List, create, and edit memos using the documented Memos REST API.
- Edit/preview switch with Markdown rendering, shell code highlighting, and copyable code blocks.
- Formatting toolbar for headings, code blocks, lists, tasks, bold, italic, strike-through, inline code, links, images, quotes, tables, horizontal rules, indent, and outdent.
- Continue list/task/quote markers when pressing Enter.
- Save with the on-screen button or Ctrl+S; new notes open directly into the keyboard editor.
- Start in local-only mode or connect to a Memos server. Online saves under 9 KiB go directly to Memos; offline saves remain in app-private storage until you tap Refresh.
- Refresh manually to check the server and upload eligible queued notes. Notes of 9 KiB or larger stay on-device permanently and are never uploaded.
- Export all local notes to a ZIP from the offline-only button at the bottom left.
- Search notes on the server with CEL content/tag filters alongside locally saved notes. Search opens from the Refresh row; swipe a note left and confirm before deleting.
- Keep notes of 9 KiB or larger on-device permanently; they are never sent to the Memos API.
- Jump to a heading from the editor/preview heading picker.
- Build arm64, armeabi-v7a, and universal APKs with the GitHub Actions workflow.

The attached markdown editor's folder picker and local Markdown file manager are intentionally not included: the editor is adapted for editing notes. Image syntax is preserved in Markdown; images are not currently rendered inline in preview.

## GitHub Actions builds

Upload this project to a GitHub repository, then push to `main` or `master`, open a pull request, or run **Actions → Build & Release umeos APKs → Run workflow**. The workflow uploads three APKs as a build artifact. Create and push a version tag such as `v1.2.0` to publish a GitHub Release.

Release builds require a signing key so later updates can install over earlier releases. Generate a new key and configure these **repository Actions secrets** before creating a version tag:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | Base64 text of your `.jks` signing keystore |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Alias chosen when generating the key |
| `KEY_PASSWORD` | Key password (may equal the keystore password) |

For non-tag builds, if these secrets aren't configured, the workflow signs with the debug key. Debug-signed APKs are suitable for trying the app but cannot be used as upgrades to a release-signed app.

## Set the signing secrets from Termux

Install GitHub CLI and authenticate with access to the repository:

```sh
pkg update
pkg install gh openjdk-17
gh auth login
```

In the cloned repository directory, generate a **new key that you keep backed up**. Enter the same password twice if prompted:

```sh
keytool -genkeypair -v -keystore memos-release.jks -alias memos -keyalg RSA -keysize 2048 -validity 10000
base64 -w 0 memos-release.jks > memos-release.b64
```

Set the repo selector once if `gh` can't infer it (replace with `OWNER/REPOSITORY`):

```sh
gh repo set-default OWNER/REPOSITORY
```

Create/update the four repository secrets. The three commands without `--body` prompt securely for their values:

```sh
gh secret set KEYSTORE_BASE64 < memos-release.b64
gh secret set KEYSTORE_PASSWORD
gh secret set KEY_ALIAS --body memos
gh secret set KEY_PASSWORD
```

Delete the temporary base64 copy after setting it, and never commit either key file:

```sh
rm memos-release.b64
```

The ZIP intentionally excludes the keystore and base64 key bundled inside the supplied `mark-main.zip`; those files should not be reused or uploaded. Keep your new `.jks` file in a safe offline backup. Losing it means you cannot sign app updates with the same identity.

## Build locally

With JDK 17 and Android SDK Platform 36 installed:

```sh
./gradlew :app:assembleDebug
```

The debug APK is written under `app/build/outputs/apk/`. On Windows use `gradlew.bat :app:assembleDebug`.

## Connect the app

- Default URL: `http://localhost:5230/` (useful when Memos runs on the same Android device, such as in Termux).
- Android emulator reaching Memos on the host computer: change the URL to `http://10.0.2.2:5230`.
- Physical device: use an HTTPS URL, or a reachable LAN URL with the Memos server listening on the LAN.
- Create a PAT in Memos, enter it in the app, and tap **Verify and connect**.

The original 14 Memos API specification Markdown files are included for reference.
---
> Code is GPLv3. Icons and branding are not; see TRADEMARK.md
