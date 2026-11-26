# Kharcha

A personal Android spend tracker that reads your bank debit SMS automatically, asks you what each payment was for, and gives you Splitwise-style splits, Excel exports, trends, and in-app updates.

## Features

- **Automatic capture.** Debit SMS from HDFC, Axis, SBI and ICICI are parsed directly. Other banks go through a generic debit parser. Credits, OTPs, refunds and personal texts are ignored.
- **Instant categorisation.** Each new payment raises a popup with Save, Later and Ignore. Only that payment is shown, not your whole backlog.
- **Split payments.** Split any payment equally, by percentage, or by exact amount. Add people from your contacts or type a name. A live status shows what is still unassigned, with a "Give to me" shortcut. Your expense always counts only your share.
- **Who owes you.** The Owes tab lists outstanding amounts per person, with per-payment or bulk "Settled".
- **Cards and methods.** Each card (for example 0602 and 1145) and each payment method has its own colour and name. Overview and Activity filter by card.
- **Customise.** Add, rename, recolour and delete categories; change payment-method colours; name your cards.
- **Export.** Export any period to a formatted `.xlsx` with split and card columns and totals.
- **Trends.** Weekly (default) and monthly bars by category.
- **Updates.** The Updates tab checks this repository for a new version and installs it in-app.

## Tech stack

| Area | Choice |
| --- | --- |
| Language / UI | Kotlin, Jetpack Compose, Material 3 |
| Storage | Room (SQLite), schema version 2 with a migration |
| SMS | `BroadcastReceiver` for `SMS_RECEIVED`, filtered to alphanumeric bank senders |
| Export | Hand-written OOXML zip (no extra libraries), shared through a `FileProvider` |
| Updates | GitHub raw files + commits API, `PackageInstaller` via `ACTION_VIEW` |
| Tests | JUnit 4 (SMS parsing, split maths, version comparison) |

Minimum Android 8.0 (API 26). Target API 34.

## Project layout

```
app/src/main/java/com/spendcalci/
  MainActivity.kt          Tabs, edit sheet, app shell
  CategoryPopupActivity.kt Popup for a newly detected payment
  SmsReceiver.kt           Receives bank SMS and raises the popup
  SmsParser.kt             Bank-specific and generic debit parsing
  Db.kt                    Room entities, DAOs, migration
  Store.kt                 Categories, cards and colours (observable)
  Split.kt / SplitPicker.kt Split model, encoding, and editor UI
  OverviewScreen.kt        Totals, method/card breakdown, donut, flow
  TransactionsScreen.kt    Activity list, filters, export dialog
  DebtsScreen.kt           Owes tab
  TrendsScreen.kt          Weekly and monthly trends
  CustomizeScreen.kt       Categories, colours, card names
  AddSheet.kt              Manual expense entry
  Export.kt                Excel writer
  Updater.kt               Update checks, download, install
  UpdatesScreen.kt         Updates tab
  SpendWidget.kt           Home-screen widget
scripts/publish-update.ps1 Build, publish and push an update
updates/                   Latest APK and latest.json (read by the app)
```

## Build and run

Requirements: Android Studio or the Android SDK, JDK 17, and a device or emulator on Android 8.0+.

```powershell
.\gradlew.bat testDebugUnitTest   # unit tests
.\gradlew.bat installDebug        # install on a connected phone
```

Grant SMS and notification permissions on first launch. Contacts access is optional and only used by the split editor.

## Updates

The app reads two files from this repository on every launch and every time you open the Updates tab:

1. `updates/latest.json` describes the newest version:
   ```json
   {
     "versionName": "1.2",
     "versionCode": 3,
     "notes": "Short release notes",
     "apk": "updates/Kharcha-1.2.apk"
   }
   ```
2. The APK named in that file.

The app first asks the GitHub API for the latest commit on `main`, then reads both files from that exact commit. This avoids stale CDN copies after a push. If `versionName` is newer than the installed version, the Updates tab shows it and a badge appears on the tab. Tapping **Download & install** downloads the APK, verifies it, and opens Android's installer. The first time, Android asks you to allow installs from Kharcha. Your data is kept because the APK is signed with the same key.

### Publishing an update

1. Bump `versionName` and `versionCode` in `app/build.gradle.kts`.
2. Commit and push the source changes.
3. Run the publish script with the release notes:
   ```powershell
   .\scripts\publish-update.ps1 -Notes "What changed in this release"
   ```

The script builds the debug APK, replaces the previous APK in `updates/`, rewrites `latest.json`, and commits and pushes `updates/`.

### Important notes

- **Keep the signing key the same.** Android only installs an update over an app signed with the same key. Debug builds use the key in `%USERPROFILE%\.android\debug.keystore`. Build updates on the machine that made the original install, or the install will fail with a signature conflict.
- **Each update adds about 9 MB to git history.** That is fine for a personal app. If it grows, move APKs to GitHub Releases.
- The repo must stay public for the app to read it without a token.

## Data and privacy

Transactions are stored only on the phone (`spend.db`). The app does not send SMS, contacts or expenses anywhere. The only network calls are to `raw.githubusercontent.com` and `api.github.com` to check for updates.
