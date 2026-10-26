# Monbooru Wallpapers for Muzei

A [Muzei](https://github.com/romannurik/muzei) live-wallpaper source plugin for
[Monbooru](https://github.com/sdidas/monbooru) — pick one of your Monbooru
collections and Muzei turns it into a rotating wallpaper feed on your phone.

Developed by [Atticus](https://github.com/Atticu55), with Ana Phampyk as
product owner, chief complainer, and finder of the actual bug after six
builds of the vampire chasing DNS ghosts.

## Features

- Pick any Monbooru collection from a searchable list in-app
- Syncs the full collection (paginated, 200/page, up to 10k images) into
  Muzei's artwork queue
- Images download once into the app's private cache and are handed to Muzei
  as plain files — no FileProvider URIs, which Muzei's artwork preloader
  can't open cross-app
- Post-sync self-check opens every stored image exactly the way Muzei does
  (decode-test), so the status line reports the real failure instead of a
  false "OK"
- Tailscale-friendly: MagicDNS names that Android's private DNS won't
  resolve fall back to querying the Tailscale resolver (100.100.100.100)
  directly
- Only wifi / only-charging / battery-not-low constraints, standard Muzei
  behaviour, new wallpapers picked up automatically when Muzei asks for more
- Cache self-cleans: changing collections deletes the previous set

## Building

Requires JDK 17 and the Android SDK (API 35). `./build.sh` builds inside
Docker instead (`gradle:8.12-jdk17` + a shared SDK volume), so the host
stays clean.

The project signs with a persistent debug keystore (`komori-debug.keystore`,
not in git) so every new APK installs over the previous one and Muzei keeps
its provider selection. Create your own once:

```
keytool -genkeypair -v -keystore komori-debug.keystore \
  -alias komori -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass <your-password> -keypass <your-password> \
  -dname "CN=komori"
```

`app/build.gradle` reads the store password from `KOMORI_STORE_PASS`,
falling back to the placeholder for local debug builds.

Then `./gradlew assembleDebug` (or `./build.sh`), APK lands in
`app/build/outputs/apk/debug/`.

## Setup

1. Install the APK, open Muzei, pick **Monbooru Wallpapers**
2. In the plugin settings: server URL (e.g. `https://monbooru.example.com`),
   API token, then **Choose collection**
3. Sync once; the status line shows live progress and the self-check result

The Monbooru server URL and API token never leave the app; the token is
stored in Android's encrypted preferences.

## License

MIT
