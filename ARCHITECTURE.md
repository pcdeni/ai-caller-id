# AI Caller ID — Native Android App: Architecture Contract

This document is the single source of truth for the implementation. Writers implement EXACTLY the
names, signatures, and resource identifiers specified here. Any deviation must be reported.

## Product behavior (locked by product owner)

- On incoming call, the app gets the number via `CallScreeningService`, never blocks or delays the
  call, and shows a floating overlay card with an AI-generated caller summary.
- Lookup runs **directly from the device to the AI provider** using the user's own API key (BYOK).
  There is NO app server. Two providers behind the `LookupClient` interface, selectable in
  Settings with per-provider keys: Gemini (`gemini-3.7-flash` + Google Search grounding; needs a
  billing-enabled key) and Groq (`groq/compound-mini` with built-in web search, `groq/compound`
  fallback; free tier, no credit card).
- Privacy modes: `AUTO` (scan every incoming call) or `ON_DEMAND` (overlay shows a "Scan caller"
  button; nothing leaves the device until tapped).
- Monetization is a one-time unlock (Play Billing one-time in-app product, integrated later).
  v1 ships a stub gate (persisted boolean `appUnlockedStub`, UI switch labeled as stub). When
  locked: no scanning, manual lookup blocked with an explanatory message.
- Results are cached locally (repeat callers resolve instantly, offline, and save quota).
- UI is e-ink friendly: no animations, solid opaque colors, high contrast, bold text.

## Environment / versions (locked — do not change)

| Item | Value |
|---|---|
| Project root | the `android/` directory of the repository |
| Package / applicationId | `com.pcdeni.aicallerid` |
| Kotlin source root | `app/src/main/java/com/pcdeni/aicallerid/` |
| Gradle | 8.11.1 (wrapper distributionUrl `https://services.gradle.org/distributions/gradle-8.11.1-bin.zip`) |
| AGP | 8.7.3 |
| Kotlin | 2.0.21 |
| compileSdk / targetSdk | 34 |
| minSdk | 29 |
| Java/Kotlin target | 17 (`sourceCompatibility`/`targetCompatibility` 17, `jvmTarget` 17; NO toolchain block) |
| local.properties | `sdk.dir=<path to your Android SDK>` (gitignored, machine-specific) |

Dependencies (exact coordinates, no others, no KSP/kapt/Compose/Hilt/Room, nothing that needs GMS):

```
androidx.core:core-ktx:1.13.1
androidx.appcompat:appcompat:1.7.0
com.google.android.material:material:1.12.0
androidx.constraintlayout:constraintlayout:2.1.4
androidx.lifecycle:lifecycle-runtime-ktx:2.8.7
androidx.recyclerview:recyclerview:1.3.2
org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0
com.squareup.okhttp3:okhttp:4.12.0
androidx.security:security-crypto:1.1.0-alpha06
```

`viewBinding = true`. Version catalog NOT used — inline versions in `app/build.gradle.kts`.
Root `build.gradle.kts` declares plugins with `apply false`; `settings.gradle.kts` uses
`pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }` and
`dependencyResolutionManagement { repositories { google(); mavenCentral() } }`, `include(":app")`,
`rootProject.name = "ai-caller-id"`.

## File ownership

**W1 — build system, manifest, shared resources:**
```
android/local.properties
android/settings.gradle.kts
android/build.gradle.kts
android/gradle.properties
android/gradle/wrapper/gradle-wrapper.properties
android/.gitignore
android/README.md                     (how to build, install, simulate a call on emulator)
android/app/build.gradle.kts
android/app/proguard-rules.pro
android/app/src/main/AndroidManifest.xml
android/app/src/main/res/values/strings.xml
android/app/src/main/res/values/colors.xml
android/app/src/main/res/values/themes.xml
android/app/src/main/res/values/dimens.xml
android/app/src/main/res/xml/backup_rules.xml
android/app/src/main/res/xml/data_extraction_rules.xml
android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml
android/app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml
android/app/src/main/res/drawable/ic_launcher_foreground.xml   (simple vector: phone + shield)
```

**W2 — telephony + overlay:**
```
.../screening/CallScreenerService.kt
.../overlay/OverlayService.kt
.../overlay/OverlayViewController.kt
.../notify/NotificationHelper.kt
android/app/src/main/res/layout/view_overlay_card.xml
android/app/src/main/res/drawable/bg_overlay_card.xml
android/app/src/main/res/drawable/bg_badge_risk.xml
android/app/src/main/res/drawable/bg_button_primary.xml
android/app/src/main/res/drawable/bg_button_outline.xml
```

**W3 — AI client, data, main UI:**
```
.../App.kt
.../MainActivity.kt
.../ai/GeminiClient.kt
.../model/CallerIntel.kt
.../data/IntelRepository.kt
.../data/Prefs.kt
.../util/RoleHelper.kt
.../history/HistoryAdapter.kt
android/app/src/main/res/layout/activity_main.xml
android/app/src/main/res/layout/item_history.xml
```

## Shared contracts (exact signatures)

### model/CallerIntel.kt (W3)

```kotlin
data class SourceRef(val title: String, val uri: String)

data class CallerIntel(
    val phoneNumber: String,
    val callerName: String,
    val callerCategory: String,     // one of the categories in the prompt, or "Unknown"
    val riskLevel: String,          // "Safe" | "Low Risk" | "Medium Risk" | "High Risk / Scam"
    val isSpam: Boolean,
    val spamScore: Int,             // 0..100
    val location: String,
    val conciseSummary: String,
    val keyHighlights: List<String>,
    val recommendedAction: String,  // "Answer" | "Caution" | "Block/Decline" | "Mute"
    val eInkText: String,           // 1-line high-contrast string
    val sources: List<SourceRef>,
    val grounded: Boolean,          // false when the ungrounded fallback produced this
    val timestampMs: Long,
    val fromCache: Boolean = false,
) {
    fun toJson(): org.json.JSONObject
    companion object {
        fun fromJson(o: org.json.JSONObject): CallerIntel?          // returns null on malformed
        fun fromModelOutput(raw: String, phoneNumber: String, sources: List<SourceRef>,
                            grounded: Boolean): CallerIntel
        // fromModelOutput: strip Markdown code fences if present, parse leniently with
        // org.json, apply the same defaulting rules as the web prototype (missing name ->
        // "Number <n>", missing riskLevel derived from isSpam, etc.). Never throws.
    }
}
```

### data/Prefs.kt (W3)

```kotlin
enum class ScanMode { AUTO, ON_DEMAND }

class Prefs(context: Context) {
    var apiKey: String?                 // EncryptedSharedPreferences; wrap creation in
                                        // try/catch, fall back to plain SharedPreferences
                                        // named "prefs_fallback" if keystore fails
    var scanMode: ScanMode              // default AUTO
    var appUnlockedStub: Boolean        // default true (one-time purchase stub)
}
```

### data/IntelRepository.kt (W3)

```kotlin
class IntelRepository(private val context: Context, private val prefs: Prefs,
                      private val gemini: GeminiClient) {
    val history: kotlinx.coroutines.flow.StateFlow<List<CallerIntel>>  // newest first, cap 200
    suspend fun lookup(rawNumber: String, forceFresh: Boolean = false): Result<CallerIntel>
    fun clearHistory()
}
```
- Cache key: `rawNumber.filter { it.isDigit() }`. Empty digits -> failure Result.
- No API key configured -> `Result.failure(IllegalStateException("API key missing"))` — call
  sites map this to the string resource `error_no_key`.
- Persist history as a JSON array in `File(context.filesDir, "history.json")`, loaded lazily,
  written on each change on `Dispatchers.IO`. Cache hits return `copy(fromCache = true)` and
  move the entry to the top of history.

### ai/GeminiClient.kt (W3)

```kotlin
class GeminiClient {
    suspend fun lookup(phoneNumber: String, apiKey: String): Result<CallerIntel>
}
```
- Endpoint: `POST https://generativelanguage.googleapis.com/v1beta/models/gemini-3.7-flash:generateContent`
- API key via HTTP header `x-goog-api-key` (NEVER in the URL — keeps it out of logs).
- OkHttp with 15 s call timeout. Execute on `Dispatchers.IO`.
- Request body:
```json
{
  "system_instruction": {"parts": [{"text": "<SYSTEM_TEXT>"}]},
  "contents": [{"parts": [{"text": "<PROMPT>"}]}],
  "tools": [{"google_search": {}}],
  "generationConfig": {"temperature": 0.2}
}
```
  **Do NOT set `responseMimeType`** — JSON response mode is incompatible with the search tool;
  the prompt demands JSON and the parser strips code fences.
- Extract text from `candidates[0].content.parts[*].text` (join). Extract sources from
  `candidates[0].groundingMetadata.groundingChunks[*].web` (`uri`, `title`).
- **Grounding fallback:** if the HTTP status is 4xx and the error body mentions the tool being
  unsupported/unavailable (case-insensitive contains "tool" or "search" in error message), retry
  once WITHOUT the `tools` array and mark the result `grounded = false`. Other error mapping:
  400/401/403 -> "Invalid or unauthorized API key" (`error_bad_key`), 429 -> "Rate limit or
  quota exceeded" (`error_quota`), other -> generic with HTTP code.
- Never log the key or the full request. Log tag `"GeminiClient"`, errors only.

- SYSTEM_TEXT: `You are an automated caller ID intelligence engine. Perform live web research on the phone number and return strict JSON only, no prose.`
- PROMPT (with `$number` substituted):

```
You are a real-time incoming caller ID intelligence engine for mobile phones.
Analyze and reverse lookup this incoming phone number using live Google search results, directory listings, spam databases, consumer reports, and official business listings:

PHONE NUMBER TO SEARCH: "$number"

Your primary target is to extract accurate caller identity, business name, spam risk level, and a CONCISE SUMMARY (15-25 words) suitable for an incoming call screen or E-Ink phone display.

Respond ONLY with valid JSON matching this structure:
{
  "callerName": "Full Business Name OR Caller Title OR 'Unidentified Robocall' OR 'Unknown Caller'",
  "callerCategory": "Business" | "Telemarketer/Spam" | "Financial/Bank" | "Medical/Clinic" | "Government/Public" | "Courier/Delivery" | "Personal" | "Scam/Fraud" | "Unknown",
  "riskLevel": "Safe" | "Low Risk" | "Medium Risk" | "High Risk / Scam",
  "isSpam": true or false,
  "spamScore": number between 0 and 100,
  "location": "City, State, Country or Carrier/Toll Free details",
  "conciseSummary": "15 to 25 words executive summary explaining who this caller is and why they might be calling.",
  "keyHighlights": ["Point 1", "Point 2", "Point 3"],
  "recommendedAction": "Answer" | "Caution" | "Block/Decline" | "Mute",
  "eInkOptimizedText": "Short high-contrast 1-line text for e-paper displays"
}
```

- Input guard (mirrors the web prototype): before sending, validate `phoneNumber` — reject if it
  contains letters or is longer than 25 chars, digits-only length must be 4..15
  (Result.failure, message from `error_bad_number`). This is the anti-prompt-injection gate.

### App.kt (W3)

```kotlin
class App : Application() {
    companion object { lateinit var instance: App; private set }
    val prefs: Prefs by lazy { Prefs(this) }
    val gemini: GeminiClient by lazy { GeminiClient() }
    val repository: IntelRepository by lazy { IntelRepository(this, prefs, gemini) }
    override fun onCreate() // sets instance, calls NotificationHelper.ensureChannel(this)
}
```

### notify/NotificationHelper.kt (W2)

```kotlin
object NotificationHelper {
    const val CHANNEL_ID = "caller_intel"
    fun ensureChannel(context: Context)
    fun postIntel(context: Context, intel: CallerIntel)      // title = callerName + risk, text = conciseSummary
    fun postScanPrompt(context: Context, number: String)     // action PendingIntent starts OverlayService ACTION_SCAN_NOW
    fun postError(context: Context, number: String, message: String)
}
```
Notification IDs: derive from the number's hashCode so repeat calls update in place.
Tapping a notification opens `MainActivity`. Use `PendingIntent.FLAG_IMMUTABLE`.

### overlay/OverlayService.kt (W2)

```kotlin
class OverlayService : Service() {
    companion object {
        const val ACTION_INCOMING = "com.pcdeni.aicallerid.INCOMING"   // extras: EXTRA_NUMBER, EXTRA_AUTO (Boolean)
        const val ACTION_SCAN_NOW = "com.pcdeni.aicallerid.SCAN_NOW"   // extras: EXTRA_NUMBER
        const val ACTION_PREVIEW  = "com.pcdeni.aicallerid.PREVIEW"    // demo card with canned data, no network
        const val EXTRA_NUMBER = "number"
        const val EXTRA_AUTO = "auto"
        fun startIncoming(context: Context, number: String, auto: Boolean)
        fun startPreview(context: Context)
    }
}
```
Behavior:
- `onStartCommand` routes by action. Not sticky. `onBind` returns null.
- If `Settings.canDrawOverlays(this)` is false: degrade to notifications —
  AUTO -> run lookup, `postIntel`/`postError`; ON_DEMAND -> `postScanPrompt`; then `stopSelf()`.
- With overlay permission: `OverlayViewController` attaches ONE card view via `WindowManager`
  (`TYPE_APPLICATION_OVERLAY`, `FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCH_MODAL`, width MATCH_PARENT
  minus margins, `PixelFormat.OPAQUE`, gravity TOP, y-offset 48dp). A new call replaces the card
  content. Card states: PROMPT (number + Scan button, ON_DEMAND), SCANNING (number +
  "Analyzing caller…"), RESULT (full card), ERROR (message + Retry button).
- Lookup: `CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)` created in the service,
  cancelled in `onDestroy`; calls `App.instance.repository.lookup(number)`.
- Auto-dismiss: remove view + `stopSelf()` 45 s after last state change (Handler, reset per state).
  Dismiss button does the same immediately. Always remove the view in `onDestroy` (guard
  against double-remove).
- NO animations of any kind (e-ink).

### overlay/OverlayViewController.kt (W2)

Owns view inflation (`view_overlay_card.xml`), state rendering, and the risk badge styling:
- riskLevel "High Risk / Scam" -> badge background tint `@color/risk_red`, "Medium Risk" ->
  `@color/risk_amber`, "Low Risk" -> `@color/risk_gray`, "Safe" -> `@color/risk_green`; badge text
  white bold. `fromCache` results show `@string/overlay_cached_tag` appended to the status line.
  Ungrounded results (grounded=false) show `@string/overlay_ungrounded_tag`.
Callbacks wired by OverlayService: `onScan`, `onRetry`, `onDismiss`.

### screening/CallScreenerService.kt (W2)

```kotlin
class CallScreenerService : android.telecom.CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details)
}
```
- If `callDetails.callDirection != Call.Details.DIRECTION_INCOMING` -> respond default, return.
- ALWAYS immediately `respondToCall(callDetails, CallResponse.Builder().build())` (never block,
  never delay — the lookup must not gate the response).
- Number: `callDetails.handle?.schemeSpecificPart`; null/blank (withheld number) -> do nothing.
- If `!App.instance.prefs.appUnlockedStub` -> do nothing (silent).
- Else `OverlayService.startIncoming(this, number, auto = prefs.scanMode == ScanMode.AUTO)`.

### util/RoleHelper.kt (W3)

```kotlin
object RoleHelper {
    fun hasScreeningRole(context: Context): Boolean          // RoleManager.isRoleHeld(ROLE_CALL_SCREENING)
    fun screeningRoleIntent(context: Context): Intent        // RoleManager.createRequestRoleIntent
    fun hasOverlayPermission(context: Context): Boolean      // Settings.canDrawOverlays
    fun overlayPermissionIntent(context: Context): Intent    // ACTION_MANAGE_OVERLAY_PERMISSION + package uri
    fun needsNotificationPermission(context: Context): Boolean  // SDK >= 33 && not granted
}
```

### MainActivity.kt + activity_main.xml (W3)

Single scrollable screen (NestedScrollView > vertical LinearLayout), sections:
1. **Setup checklist** — four rows (screening role, overlay permission, notification permission,
   API key). Each row: status dot (●, colored via `risk_green`/`risk_red`), label, action Button.
   Role via `registerForActivityResult(StartActivityForResult)`; notification permission via
   `RequestPermission` contract; API key row opens `showApiKeyDialog()` — a MaterialAlertDialog
   with a password-transform EditText prefilled from prefs, Save/Clear buttons, and helper text
   `@string/key_dialog_help` linking users to aistudio.google.com/app/apikey (plain text, no
   browser launch needed).
2. **Settings** — RadioGroup for scan mode (AUTO / ON_DEMAND), MaterialSwitch for
   "App unlocked (one-time purchase stub)", both persisted to Prefs immediately.
3. **Manual lookup** — EditText (inputType phone) + "Look up" Button + result TextView block
   (name, badge line, summary, sources count). Blocked with `error_subscription` when stub
   inactive. Runs `repository.lookup(number, forceFresh = false)` in `lifecycleScope`.
4. **Preview** — Button "Preview overlay card" -> `OverlayService.startPreview(this)` (uses
   canned CallerIntel, lets the user see the overlay without a call; if no overlay permission,
   posts the preview as a notification).
5. **History** — RecyclerView (LinearLayoutManager, nestedScrollingEnabled=false) fed by
   `repository.history` collected in `lifecycleScope`; `HistoryAdapter` renders `item_history.xml`
   rows (name bold, number, risk badge, summary 2-line ellipsized, relative time). "Clear" button.
6. **Emulator tip** — static TextView `@string/emulator_tip` explaining
   `adb emu gsm call <number>`.
Refresh checklist state in `onResume`.

## Manifest requirements (W1)

- Permissions: `INTERNET`, `SYSTEM_ALERT_WINDOW`, `POST_NOTIFICATIONS`.
- `<application android:name=".App"` `allowBackup="true"` `fullBackupContent="@xml/backup_rules"`
  `dataExtractionRules="@xml/data_extraction_rules"` `icon="@mipmap/ic_launcher"`
  `roundIcon="@mipmap/ic_launcher_round"` `label="@string/app_name"`
  `theme="@style/Theme.AICallerID"`.
- MainActivity: exported, LAUNCHER intent filter.
- CallScreenerService:
```xml
<service android:name=".screening.CallScreenerService"
    android:permission="android.permission.BIND_SCREENING_SERVICE"
    android:exported="true">
  <intent-filter><action android:name="android.telecom.CallScreeningService"/></intent-filter>
</service>
```
- OverlayService: `android:exported="false"`, no foreground service type (it is a plain service).

## Resource name registry (W1 creates; W2/W3 reference — names are law)

**colors.xml:** `ink_black #111111`, `paper_white #FFFFFF`, `paper_bg #F4F3ED`, `accent_blue #2563EB`,
`risk_red #DC2626`, `risk_amber #D97706`, `risk_green #059669`, `risk_gray #6B7280`,
`border_gray #D1D5DB`, `text_secondary #4B5563`, `ic_launcher_background #111111`

**themes.xml:** `Theme.AICallerID` parent `Theme.Material3.Light.NoActionBar`; window background
`paper_bg`; `colorPrimary` `ink_black`, `colorSecondary` `accent_blue`. Status bar `paper_bg`,
light status bar icons true.

**dimens.xml:** `card_corner 12dp`, `card_padding 16dp`, `screen_padding 16dp`, `overlay_margin 12dp`

**strings.xml keys (exact):** `app_name` ("AI Caller ID"), `checklist_title`, `role_row_label`,
`role_action`, `overlay_row_label`, `overlay_action`, `notif_row_label`, `notif_action`,
`key_row_label`, `key_action_set`, `key_action_change`, `key_dialog_title`, `key_dialog_help`,
`key_dialog_save`, `key_dialog_clear`, `settings_title`, `scan_mode_auto`, `scan_mode_on_demand`,
`unlock_stub_label`, `lookup_title`, `lookup_hint`, `lookup_action`, `preview_action`,
`history_title`, `history_clear`, `history_empty`, `emulator_tip`, `overlay_scanning`,
`overlay_scan_action`, `overlay_retry`, `overlay_dismiss`, `overlay_cached_tag` ("cached"),
`overlay_ungrounded_tag` ("no live search"), `overlay_privacy_prompt`, `error_no_key`,
`error_bad_key`, `error_quota`, `error_bad_number`, `error_locked`, `error_network`,
`notif_channel_name`, `notif_scan_prompt_title`, `status_done`, `status_missing`.
All user-visible text in W2/W3 code MUST come from these resources (no hardcoded literals).

**drawables (W2):** `bg_overlay_card` (rect, `paper_white` fill, 2dp `ink_black` stroke,
`card_corner` radius), `bg_badge_risk` (rect, white fill placeholder — tinted at runtime, 4dp
radius), `bg_button_primary` (rect `ink_black` fill, white text assumed, `card_corner` radius),
`bg_button_outline` (rect transparent fill, 2dp `ink_black` stroke, `card_corner` radius).

**view_overlay_card.xml IDs (W2):** `overlayRoot`, `overlayNumber`, `overlayStatus`, `overlayName`,
`overlayBadge`, `overlayCategory`, `overlaySummary`, `overlaySources`, `overlayScanButton`,
`overlayRetryButton`, `overlayDismissButton`.

**activity_main.xml IDs (W3):** must match MainActivity's ViewBinding usage (same writer).

## Code style

- Kotlin official style, 4-space indent, no wildcard imports.
- Comments only where Android APIs force non-obvious constraints (e.g. why respondToCall is
  called before the lookup). No decorative comments.
- Every class compiles against compileSdk 34 with `minSdk 29` guards where APIs demand
  (`Build.VERSION.SDK_INT >= 33` for POST_NOTIFICATIONS).
