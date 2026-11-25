# Movio

Movies and TV shows for Android phones.

## Releases

Bump `versionName` and `versionCode` in `version.properties` and push to `main`.
The Release workflow builds a signed APK and publishes it as the latest GitHub Release.
Settings → Check for updates in the app downloads and installs it.

## Build

```
gradle assembleDebug
```

Release signing uses the `MOVIO_KEYSTORE_BASE64`, `MOVIO_KEYSTORE_PASSWORD`,
`MOVIO_KEY_ALIAS` and `MOVIO_KEY_PASSWORD` repository secrets.

Font: Plus Jakarta Sans (SIL Open Font License, `app/src/main/assets/fonts/PlusJakartaSans-OFL.txt`).
