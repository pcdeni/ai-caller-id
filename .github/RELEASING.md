# Releasing

Releases are built and published by `.github/workflows/release.yml` whenever a tag matching `v*` is pushed.

## Checklist

1. Bump `versionCode` (integer, +1) and `versionName` (e.g. `1.0.1`) in `app/build.gradle.kts`.
2. Add a changelog for the new version code: `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (plain text, a few lines, 500 characters max).
3. Commit on `main` and make sure the **Build** workflow is green.
4. Tag and push the tag:

   ```sh
   git tag v<versionName>      # e.g. git tag v1.0.1
   git push origin v<versionName>
   ```

5. The **Release** workflow builds `assembleRelease` with the signing secrets below, names the file `ai-caller-id-v<versionName>.apk`, and creates a GitHub Release with auto-generated notes and the APK attached.
6. Check the release page, edit the generated notes if needed. IzzyOnDroid and Obtainium pick the APK up from GitHub Releases automatically.

If the workflow fails at "Check that signing secrets are configured", the secrets are missing; add them as described below and re-run the job.

## One-time setup: GitHub secrets

The release workflow needs four repository secrets (repository **Settings -> Secrets and variables -> Actions -> New repository secret**):

| Secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | The `.jks` keystore file, base64-encoded (see below) |
| `KEYSTORE_PASSWORD` | The keystore (store) password |
| `KEY_ALIAS` | The key alias, e.g. `upload` |
| `KEY_PASSWORD` | The key password |

These correspond to the entries in `keystore.properties.example`. Create the keystore if you do not have one yet:

```sh
keytool -genkeypair -v -keystore upload-keystore.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000
```

Base64-encode the keystore and copy it to the clipboard, then paste it as the value of `KEYSTORE_BASE64`:

PowerShell (Windows):

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("upload-keystore.jks")) | Set-Clipboard
```

Linux / macOS:

```sh
base64 -w0 upload-keystore.jks   # macOS: base64 -i upload-keystore.jks
```

Keep the `.jks` file and the passwords backed up offline. Both `*.jks` and `keystore.properties` are gitignored; never commit them.

## F-Droid

F-Droid builds the app from the git tag itself, on its own infrastructure, and signs it with F-Droid's key. It does not use the GitHub Release APK and needs none of the secrets above. Only the tag, the version bump, and the changelog file matter for that channel.
