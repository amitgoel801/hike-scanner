# Hike Scan (Android)

Return-scanning app for Hike Store staff. Works with the **Return scanning** section of
HikeOne (app.hikestore.in/return_scan).

- Login with the HikeOne user (password + Authenticator or email code).
- Scanner as keyboard (Enter / Tab / no suffix) or broadcast (Zebra DataWedge, Honeywell,
  Urovo, Newland, iData, Sunmi, Chainway, ...).
- Every scan is saved on the phone first, then sent; works offline; nothing is lost if the
  app closes or the phone switches off.
- Android 5.0+ (minSdk 21), target Android 14.

Build: GitHub Actions (`.github/workflows/build.yml`) builds `HikeScan-<version>.apk` on every
push to main. Signing key: repository secrets `KS_B64` (base64 keystore) and `KS_PASS`.

Version: bump `versionCode`/`versionName` in `app/build.gradle` **and** `AppInfo.java`.
