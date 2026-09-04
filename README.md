# SharedPreferences Override — LSPatch / Xposed module

A fully standalone, self-contained Xposed module that intercepts Android
`SharedPreferences` reads and writes **at the framework level** and injects
override values defined in `assets/sp_rules.json`. It contains **zero
app-specific hardcoding** (no package names, no key names, no target values)
and is designed to be embedded into a target APK with LSPatch.

## How it works

1. `handleLoadPackage` hooks `android.app.Application.onCreate` (with an
   `android.app.Instrumentation.callApplicationOnCreate` fallback) using
   `beforeHookedMethod`. The running app's `Application` instance is captured
   from `param.thisObject` on the framework's own main thread.
2. The captured `Context` reads `assets/sp_rules.json` from the APK's
   `AssetManager` exactly once and parses it into an in-memory lookup cache
   (`get_overrides` / `put_overrides` `JSONObject`s).
3. Low-level hooks are installed on the AOSP framework implementations
   (immune to app-level ProGuard/obfuscation):
   - reads  -> `android.app.SharedPreferencesImpl` (`getString`, `getBoolean`,
     `getInt`, `getLong`, `getFloat`)
   - writes -> `android.app.SharedPreferencesImpl$EditorImpl` (`putString`,
     `putBoolean`, `putInt`, `putLong`, `putFloat`)
4. A single unified `XC_MethodHook` instance is shared by all five read
   methods, and another by all five write methods.
5. Reads (`afterHookedMethod`): when the requested key exists under
   `get_overrides`, the framework result is dropped and replaced via
   `param.setResult(typedValue)`.
6. Writes (`beforeHookedMethod`): when the key exists under `put_overrides`,
   `param.args[1]` is replaced with the typed override before the framework
   commits it to storage.

### Type-casting engine

Rule values arrive from JSON as raw strings / generic objects. The module
inspects the **primitive wrapper class of the runtime argument/result**
(`instanceof String/Boolean/Integer/Long/Float`) and converts the raw rule
value to exactly that wrapper type. When the runtime value is `null`
(e.g. a read that returned the `null` default, or `putX(key, null)` removal),
the expected type is derived from the hooked method name instead.

If a rule value cannot be cast to the expected type (for example a non-`true`/
`false` string routed to a boolean), the override is skipped and the original
value is left untouched so the app never crashes.

## Repository layout

```
app/src/main/java/com/example/spoverride/SharedPreferencesOverrideModule.java
app/src/main/assets/xposed_init
app/src/main/assets/sp_rules.json
app/src/main/AndroidManifest.xml
app/build.gradle
build.gradle
settings.gradle
gradle.properties
```

## sp_rules.json

Keys are the SharedPreferences keys read/written by the app. Values are the
forced override values; any JSON scalar type is accepted and converted to the
type the framework call actually expects.

```json
{
  "get_overrides": {
    "profile.display_name": "Override Alex",
    "profile.beta_opt_in": true,
    "analytics.session_count": 42,
    "cache.max_size_bytes": 536870912,
    "ui.theme_scale": 1.25
  },
  "put_overrides": {
    "tutorial.completed": false,
    "network.mock_enabled": true,
    "feature.flag.banner": "experimental-banner-v3",
    "sync.last_run_ms": 9999999999999,
    "network.timeout_scale": 0.5
  }
}
```

Optional: for keys whose storage type cannot be inferred, use an explicit
typed descriptor object instead of a bare scalar:

```json
{
  "get_overrides": {
    "my_pref": { "type": "Long", "value": "1752000000000" }
  }
}
```

Supported types: `String`, `Boolean`, `Integer`, `Long`, `Float`.

## Building the module

Open the repository in Android Studio (or run `./gradlew assembleRelease`).
Requirements:

- Android SDK 34 (`compileSdk`)
- `minSdk 21`
- The Xposed API is pulled in as `compileOnly` from
  `de.robv.android.xposed:api:82`. It is resolved at runtime by
  LSPatch/LSPosed, so it must never be packaged into the module.
- Keep `minifyEnabled false` for the release build; hook targets are looked up
  by name at runtime, and the module declares no resources.

The build output is `app/build/outputs/apk/release/app-release-unsigned.apk`.
Because the module is an LSPatch module (not a normal app), it does not need
signing to be used — LSPatch embeds its dex into the target.

## Embedding into a target APK with LSPatch

LSPatch supports merging a module APK into a target APK so the patched app is
fully self-contained. Use LSPatch's local/embed flow and point it at the
module APK built above; consult the LSPatch README for the exact CLI of the
version you use. Representative invocation:

```
java -jar lspatch.jar app-release.apk -m module-release.apk -o app-patched.apk
```

After patching verify the assets made it into the final APK:

```
aapt list app-patched.apk | grep -E "xposed_init|sp_rules.json"
```

`assets/sp_rules.json` must be present at the **assets root of the modified
APK**, because the module reads it through the target app's own
`AssetManager` (that is exactly what "inside the modified APK" means here).
When embedding via LSPatch the module's assets are merged in automatically.
If you use LSPatch manager mode instead (module not physically merged), add
`sp_rules.json` to the target app's `assets/` yourself before patching.

Then install `app-patched.apk` and run it.

## Observing overrides in logcat

All decisions are logged with old vs. new values under the `SPOverride` tag:

```
adb logcat -s SPOverride
```

Example output:

```
SPOverride: handleLoadPackage pkg=com.example.app process=com.example.app
SPOverride: Rules loaded from assets/sp_rules.json -> get_overrides=5, put_overrides=5
SPOverride: Hooks installed on android.app.SharedPreferencesImpl and android.app.SharedPreferencesImpl$EditorImpl.
SPOverride: GET  [profile.display_name] String old='Bob' (String) -> new='Override Alex' (String) (SharedPreferencesImpl.getString)
SPOverride: PUT  [tutorial.completed] Boolean old=true (Boolean) -> new=false (Boolean) (EditorImpl.putBoolean)
```

## Error handling / fallbacks

- Missing `assets/sp_rules.json`: logged once, the module stays idle and no
  values are touched.
- Malformed JSON / unparseable override values: logged once, the offending key
  is skipped, the original value is preserved, and the app keeps running.
- Hooks that cannot be installed (very old / vendor-modified framework): logged
  once; the module never throws into the app.

## Scope notes

- Because the module hardcodes nothing, make sure the LSPatch embed/manager
  setup activates it only for the intended target. Non-primary processes
  (providers/services with a `:name` process) are skipped automatically.
- Xposed-hooking requires the framework classes by their canonical AOSP names
  (`android.app.SharedPreferencesImpl` and
  `android.app.SharedPreferencesImpl$EditorImpl`); these names are stable
  across AOSP/stock and most vendor builds, which is why hooking them instead
  of app classes is robust against app-level obfuscation.

Use this module only on applications you own or are explicitly authorized to
test. It alters the behavior of the app it is embedded into.
