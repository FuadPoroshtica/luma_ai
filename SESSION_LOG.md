# LightAI — Session Log

Single-source reference for the LightAI project (fork of vandamd/luma → built
into the "ultimate OpenClaw client" on the Light Phone III).

Repo: `git@github.com:FuadPoroshtica/luma_ai.git` · App package: `app.lightai`
Upstream: `vandamd/luma` (Apache 2.0) · License: GPL v3 (inherited)

## Vision

Light Phone III running a purpose-built launcher that:

- Replaces LightOS's homescreen but **keeps LightOS as the dialer / SMS handler**
- Surfaces a personal AI assistant (the user's own OpenClaw gateway) as a
  first-class action on hardware buttons, kiosk surface, and overlay
- Speaks OpenClaw's gateway protocol natively — no app-switching, no curl

## Device facts (Light Phone III, model TLP301)

- **SoC**: Snapdragon 4 Gen 2 (SM4450) — *not* an 8-series flagship
- **OS**: LightOS, custom AOSP, Android 14 (`UKQ1.240321.001`), security patch
  2025-03-01, kernel 5.10.198-android12-9
- **Bootloader**: OEM-unlock-allowed after toggling Developer Options
  (`sys.oem_unlock_allowed=1`), but **not unlocked**. Locked rooting wall:
  no public LP3 firmware, no SM4450 firehose loader leaked anywhere, no
  community boot.img dump. `fastboot fetch` is gated on `userdebug` build;
  LP3 is `release-keys` → that path is closed too.
- **Hardware input scancodes** (from `getevent -lp`):

  | Hardware | Scancode | Android keycode | Notes |
  |---|---|---|---|
  | Vol up/down | 115 / 114 | KEYCODE_VOLUME_UP/DOWN | standard |
  | Home button | 102 | KEYCODE_HOME | swallowed by PhoneWindowManager — long-press uninterceptable from user space |
  | Side button A | 27 (RIGHTBRACE) | KEYCODE_CAMERA | LP3 calls this "flash" |
  | Side button B | 80 (KP2) | KEYCODE_FOCUS | |
  | Wheel click | 66 (F8) | KEYCODE_ENTER (Generic.kl: WHEEL_CLICK) | |
  | Wheel rotate | 19 / 20 (R / T) | **KEYCODE 317** (EMOJI_PICKER on this device) | direction not distinguishable |

- **Gesture letters from touchscreen** (W/E/U/O/S/L/Z/C/V/M, arrow keys) and
  Pixart optical sensor codes are interpreted only by LightOS's React Native
  bundle — dead under any third-party launcher.

## What's installed alongside LightAI

- `com.workvivo.android`, `com.aurora.store`, `dev.imranr.obtainium`,
  `de.szalkowski.activitylauncher`, `app.olauncher`, `ws.xsoh.etar`,
  `com.anthropic.claude`, `com.spotify.music`, `io.github.sds100.keymapper`,
  `at.bitfire.davdroid` (existing Light sideload), `is.audkenni.app`
  (Auðkenni — installed but refuses on GMS check), `org.microg.gms`
  (microG GmsCore user-mode build for partial FCM/location)
- `app.luma` (vanilla Luma kept as upstream reference)
- Not installed: `ai.openclaw.app` — gateway runs on user's server instead

## Hard walls (don't re-propose)

- Cannot replace Android lockscreen (needs SystemUI / root)
- Cannot install real GMS or run Google's first-party apps (signature check)
- Cannot patch LightOS launcher APK (signed with platform key `c93510f4`)
- Cannot remap KEYCODE_HOME long-press (intercepted in PhoneWindowManager)
- Cannot root via bootloader unlock alone — no boot.img to patch

## Version log

| Version | Surface | What shipped |
|---|---|---|
| **0.1.x** | rebrand | vandamd/luma → `app.lumai` (later renamed). Mechanical package rename. v0.1.0 had ClassNotFound from BSD sed `\b` not matching — fixed in 0.1.1. |
| **0.2.0** | rename | LumAI → LightAI (`app.lightai`). Action enum: GoBack, OpenAssistant, BrightnessUp/Down. |
| **0.3.0** | hardware keys | In-app `HardwareKey` enum, accessibility key filter, Settings UI for per-key Single/Double/Long mapping. Phase 3: large home buttons. Phase 4: AI prompt overlay (text + voice via RecognizerIntent). |
| **0.3.1** | polish | HOME double-tap fallback in `onNewIntent`; large-mode overflow toast. |
| **0.4.0** | OpenClaw | `Action.OpenClaw` action + tier-2 fallback in AI overlay (`ASK_OPENCLAW` custom intent). |
| **0.5.0** | kiosk | `KioskFragment`: 120sp clock + "Ask Claw" tile. Set as default `startDestination` when enabled. `backToHomeScreen` targets kiosk. |
| **0.5.1–0.5.4** | polish | Kiosk tap routing fix (only "Tap here to open" hint navigates home, not full background); ENTER=Send on overlay; better hardware-key defaults (wheel single → AI Prompt, long → AI Voice, camera side → AppList, focus side → NotificationList); accessibility-off banner; tool row added at bottom (Phone/SMS/Apps text). |
| **0.6 → 0.7.x** | tool row + Key Mapper | Tool row anchored (homeAppsLayout paddingBottom 96dp), text → minimal monochrome icons (`ic_tool_phone.xml`, `ic_tool_sms.xml`, `ic_tool_apps.xml`). Three activity-aliases expose LightAI actions as intents (`app.lightai.action.OPEN_AI_PROMPT`, `OPEN_AI_VOICE`, `OPEN_CLAW`) — Key Mapper now owns hardware keys; LightAI's a11y key filter disabled to avoid contention. Volume HUD on home (Settings.System ContentObserver → 1.2s pill). |
| **0.7.0** | pairing | OpenClaw gateway pairing UI. CameraX + ML Kit standalone barcode (no GMS). EncryptedSharedPreferences for setup code. Decoder ported from `GatewayConfigResolver.kt`. |
| **0.7.1** | proguard | R8 keep rules for ML Kit / OkHttp / kotlinx.serialization / CameraX / Security crypto. |
| **0.7.2** | manual entry | Paste-from-clipboard dialog for the setup code; auto-prefills if clipboard looks valid. |
| **0.8.0–0.8.3** | gateway connect | `GatewayClient.kt` (OkHttp WebSocket). Wire format ported from `packages/gateway-client/src/client.ts`. Handshake bugs fixed across versions: frame type `evt`→`event`, client.id `lightai-android`→`openclaw-android`, **0.8.3 added Ed25519 device identity** (BouncyCastle, `DeviceIdentityStore.kt` ported from OpenClaw, signed v3 payload). |
| **0.9.0** | chat | `GatewayClient.sendChat(prompt)` (`chat.send` method), `chatStream: SharedFlow<ChatChunk>` (state delta/final/aborted/error). AI overlay routes through gateway when status=Connected, renders streamed response inline. Fallback dispatch chain preserved for offline. |

## Architecture map

```
fragment_home.xml ──────── HomeFragment ──── KioskFragment (startDestination)
       │                       │
       ├ statusBar              ├ tool row (Phone/SMS/Apps ImageView icons)
       ├ homeAppsLayout         ├ volumeHud (ContentObserver-driven)
       ├ toolRow                ├ HardwareKeyBus collector (vestigial)
       └ volumeHud              └ initToolRow / initStatusBarClickListeners

MainActivity ───────────── singleTask launcher
       │
       ├ nav graph (startDestination=kioskFragment)
       ├ handleActionIntent (app.lightai.action.* from Key Mapper)
       ├ detectHomeDoubleTap (250ms window → GoBack)
       └ HOME role + DIALER stays with LightOS

ActionService ─────────── accessibility service (key filter DISABLED in v0.7.7)
       │                  Key Mapper handles hardware buttons.
       └ Global actions still bound: goBack / showRecents / lockScreen

GatewayClient ──────────── OkHttp WebSocket to wss://claw.vemo.is:443
       │                   Singleton (.shared()). Lifecycle: connect(ctx,cfg)
       ├ Status state machine: Idle/Connecting/ChallengeReceived/
       │  Connected/Disconnected/Error
       ├ Frame protocol (port of packages/gateway-protocol/src/schema/frames.ts):
       │   {type:"req"|"res"|"event", ...}
       ├ Connect: receive challenge nonce → send signed device payload
       │   + bootstrap token via {method:"connect"}
       └ Chat: sendChat("prompt") → emits ChatChunk(runId, state, text)

DeviceIdentityStore ────── per-device Ed25519 keypair stored at
                            filesDir/lightai/identity/device.json
                            deviceId = SHA-256 hex of raw public key
                            signing via BouncyCastle Ed25519Signer
```

## Critical files

```
app/build.gradle ............ deps: BouncyCastle, OkHttp, ML Kit standalone,
                              CameraX, security-crypto, kotlinx.serialization
app/proguard-rules.pro ...... ML Kit + BouncyCastle + Okio keep rules
app/src/main/AndroidManifest.xml
                              activity-aliases for OPEN_AI_PROMPT/VOICE/CLAW
                              permissions: CAMERA, WRITE_SETTINGS,
                              SYSTEM_ALERT_WINDOW, INTERNET, QUERY_ALL_PACKAGES

data/Prefs.kt ............... gestures, hardware keys, tool row,
                              kioskEnabled, largeButtonMode, preferredAiTarget
data/SecurePrefs.kt ......... encrypted gateway setup code
data/GatewaySetupCode.kt .... decoder (port of GatewayConfigResolver)
data/DeviceIdentity.kt ...... Ed25519 keypair store (port of DeviceIdentityStore)
data/Constants.kt ........... Action enum + AppDrawerFlag

helper/ActionService.kt ..... a11y service (key filter OFF)
helper/HardwareKeyBus.kt .... SharedFlow (vestigial — Key Mapper used now)
helper/GatewayClient.kt ..... WebSocket + handshake + chat send + stream

ui/KioskFragment.kt ......... clock + date + Ask Claw tile
ui/HomeFragment.kt .......... tool row, volume HUD, gesture dispatch
ui/AiPromptOverlayFragment.kt ─ submit → gateway if connected, else Claude/ASSIST
ui/PairingFragment.kt ....... CameraX + ML Kit QR scan + paste-from-clipboard
ui/SettingsFragment.kt ...... a11y banner, gateway connect/disconnect toggle
ui/HardwareKeysFragment.kt + DetailFragment + ActionFragment ─ in-app remap UI
                              (legacy — most users now use Key Mapper)
```

## Roadmap (locked in)

| Version | Scope |
|---|---|
| **0.9.x** | Tick watchdog, auto-reconnect, manual URL/token override, error retry, unpair button |
| **1.0.0** | Dedicated `ChatFragment` with scrolling history + multi-session SQLite store |
| **1.1.0** | Streaming polish + voice push-to-talk (STT → gateway → TTS reply) |
| **1.2.0** | (DONE EARLY in 0.8.3) — Ed25519 device identity; remaining: store + reuse `deviceToken` from helloOk so subsequent connects skip re-auth |
| **1.3.0** | Operator approvals notifications, channel routing display, skill triggers as LightAI actions |
| **1.4.0** | NotificationListener → AI auto-summaries via gateway |
| **1.5.0** | Camera image → vision prompt |
| **1.6.0** | Screen-context "ask about this" via accessibility content scan |
| **1.7.0** | Calendar / contacts integration |
| **2.0.0** | App icon (replace Luma placeholder), onboarding wizard, F-Droid metadata |

## Build chain (macOS)

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools

./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Keystore: `~/.android-keys/lumai-release.jks` (password in
`~/.android-keys/lumai.password`). `local.properties` holds `sdk.dir` +
signing config. Both excluded from VCS.

## Useful adb invocations

```sh
# Fire LightAI actions (Key Mapper / Tasker can use these)
adb shell am start -a app.lightai.action.OPEN_AI_PROMPT
adb shell am start -a app.lightai.action.OPEN_AI_VOICE
adb shell am start -a app.lightai.action.OPEN_CLAW

# Watch gateway protocol
adb logcat | grep "LightAI-Gateway"
adb logcat | grep "LightAI-DeviceAuth"

# Toggle accessibility service (when system drops it)
adb shell "settings put secure enabled_accessibility_services \
  'io.github.sds100.keymapper/.system.accessibility.MyAccessibilityService:\
app.lightai/.helper.ActionService:app.luma/.helper.ActionService'"

# Set / revert home
adb shell cmd package set-home-activity app.lightai/app.lightai.MainActivity
adb shell cmd package set-home-activity com.lightos/.MainActivity   # back to LightOS
```

## Known limitations / open items

- **AI overlay streaming** completes but next-prompt UX is unfinished — after
  "final" event there's no clean "ask again" affordance; closing and reopening
  the overlay starts a new run but reuses sessionKey="main" (multi-turn works).
- **Reconnect** is not yet automatic on socket drop (v0.9.x).
- **Device token persistence**: gateway returns `deviceToken` in helloOk but
  we don't yet store/reuse it (v1.2.0 remaining work — saves re-pairing).
- **Notification listener**, **chat history persistence**, and **dedicated
  ChatFragment** are explicitly v1.0.0.
- **Voice quality**: currently uses Android's RecognizerIntent (free
  on-device STT). Quality varies by device; LP3 has no Google STT model so
  results may be poor. v1.1.0 will route voice through OpenClaw's `talk.*`
  surface instead.
- **App icon** still uses the Luma placeholder; v2.0.0 polish.

## Provenance

- LightAI is a fork of [vandamd/luma](https://github.com/vandamd/luma) — see
  `NOTICE.md` for upstream attribution and OlauncherCF lineage.
- OpenClaw protocol ports (MIT → GPL v3 compatible):
  - `GatewaySetupCode.kt` ← `apps/android/.../GatewayConfigResolver.kt`
  - `DeviceIdentity.kt` ← `apps/android/.../DeviceIdentityStore.kt`
  - `GatewayClient.kt` connect frame ← `packages/gateway-client/src/client.ts`
    (`buildDeviceAuthPayloadV3` + `assembleConnectParams`)
  - Chat send/receive ← `apps/android/.../chat/ChatController.kt`
