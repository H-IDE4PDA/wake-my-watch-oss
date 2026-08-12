# Wake My Watch

Android phone app + Wear OS companion app for owners of **OnePlus / OPPO watches**.
It compensates for limitations and bugs in the stock **OHealth + OnePlus Watch**
notification pipeline — most visibly on the **OnePlus Watch 4 (OPWWE261)**, where
stock notifications forwarded via OHealth can arrive without properly waking the
watch screen and/or with the notification sound cut off partway through.

What it does:

- Wakes the watch screen for notifications you choose.
- Corrects the truncated stock notification sound (OnePlus Watch 4).
- Optionally plays a custom sound of your choice.
- Respects phone/watch Do Not Disturb state, phone lock state, and whether the
  watch is being worn (off-wrist detection).
- Tries not to compete with things OHealth or the system already handle correctly.

Built for OnePlus Watch 4 / OnePlus Watch 2R and, more generally, other Wear OS
watches from OPPO/OnePlus, and for Android phones without proper native
DND/Bedtime sync to their watch.

## Project structure

```
core/    com.h_ide4pda.wakemywatch.core    shared protocol, settings, stores
phone/   com.h_ide4pda.wakemywatch.phone   phone app
watch/   com.h_ide4pda.wakemywatch.watch   Wear OS app
```

Both modules share one `applicationId` (`com.h_ide4pda.wakemywatch`) but build as
two separate APKs.

Stack: Kotlin, Jetpack Compose (phone) / Wear Compose (watch), Play Services
Wearable APIs (`NodeClient`, `MessageClient`, `NotificationListenerService`, off-body
sensor). No backend, no database, no analytics/telemetry — state is local
`SharedPreferences`, and the phone↔watch protocol is a small hand-rolled JSON
message format.

## Building

Requires JDK 17 and the Android SDK (Android Studio's bundled JBR/SDK work fine).

```bash
./gradlew clean :phone:assembleDebug :watch:assembleDebug
```

This produces unsigned debug APKs — no extra setup required.

### Release builds

Release builds need your own signing key. Create `keystore.properties` in the
project root (this file is gitignored and must never be committed):

```properties
storeFile=/absolute/path/to/your.jks
storePassword=...
keyAlias=...
keyPassword=...
```

If `keystore.properties` is missing, `:assembleRelease` tasks fail with a clear
error; `:assembleDebug` builds don't need it at all.

## Donations

The donation links/addresses in `PhoneMainActivity.kt` (`KOFI_URL`, `MONOBANK_URL`,
`USDT_TRC20_ADDRESS`) go to the original author. **If you fork this project and
distribute your own build, replace them with your own.**

## Known limitations

- On OnePlus/Oppo/Realme phones (OxygenOS/ColorOS), `OplusHansManager` freezes the
  whole app process when the screen turns off, independent of standard Android
  battery-optimization whitelisting — `NotificationListenerService` stops running
  and notifications are missed. No workaround found so far (whitelisting,
  foreground services, and "recent apps lock" don't help). Not observed on stock
  Android (Pixel).
- The truncated notification sound issue is confirmed on OnePlus Watch 4; not
  confirmed on Watch 2R, which may run different firmware behavior.
- Two-way DND/Bedtime sync between phone and watch is not finished — only a
  read-only sync-state indicator exists so far.
- Release signing is not preconfigured — you need to supply your own keystore.

## License

MIT — see [LICENSE](LICENSE).
