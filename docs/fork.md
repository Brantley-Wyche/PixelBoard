# Fork notes (Brantley-Wyche/PixelBoard)

This fork adds the Material 3 Expressive look (upstream issue #133) on top of
Akshayykadam/PixelBoard. The design is in
`docs/superpowers/specs/2026-11-09-m3-expressive-look-design.md`.

## One-time setup (already done on this PC)

- Java 21 and jadx in `%LOCALAPPDATA%\pixelboard-tools\`.
- Stock Gboard bundle at `%LOCALAPPDATA%\pixelboard-tools\input\gboard-18.6.3.apkm`
  (APKMirror, `18.6.3.1002251336-release-arm64-v8a`, the Android 10+ bundle
  with 6 splits; it must be a bundle, since voice typing needs its dictation
  split). The 18.5.3 bundle used up to PixelBoard 1.3.3 is kept next to it.
- GitHub CLI login with `read:packages`:
  `gh auth refresh -h github.com -s read:packages`
- Signing key converted from the shared PixelBoard key (same certificate as
  the official 1.3.x APKs, `07E18F42…`), so builds install as updates:

  ```bash
  keytool -importkeystore \
      -srckeystore tools/morphe-data/imported.keystore -srcstoretype BKS \
      -srcstorepass "" -srcalias pixelboard -srckeypass pixelboard \
      -providerpath tools/patcher.jar \
      -providerclass org.bouncycastle.jce.provider.BouncyCastleProvider \
      -destkeystore tools/patcher-data/pixelboard.keystore -deststoretype PKCS12 \
      -deststorepass pixelboard -destkeypass pixelboard -destalias pixelboard
  ```

## Build and install

With the phone plugged in and USB debugging on:

```bash
./build-fork.sh --install
```

Without `--install` it only writes `output/PixelBoard.apk`.

## Pulling in a new upstream release

```bash
git switch main
git pull upstream main
git push origin main
git switch feat/m3-expressive-look
git merge main
./build-fork.sh --install
```

If upstream moves to a new Gboard base version, download that stock bundle,
point `INPUT_APK` in `build-fork.sh` at it (or pass its path), and add the new
obfuscated name of the top-rounded keyboard module's
`applyOutline(KeyboardViewType, View)` method to `GboardExpressiveLookPatch.kt`.
Known names: 18.5.3 `Lrbm;->s(Lqcl;Landroid/view/View;)V`,
18.6.3 `Lqvg;->t(Lpwj;Landroid/view/View;)V`. To find it, unzip `base.apk`
from the bundle, find the `classes*.dex` containing
`always_top_rounded_keyboard_for_debug`, decompile that dex with jadx, and
look for the class with that flag and a private `(KeyboardViewType, View)`
method that sets an outline provider. Until it is added, the corners still
work and the sunburst, divider and spacing are skipped.

Upstream releases also come with a "PixelBoard updates" screen in the app;
don't install from it, since it replaces this build with the official one.

## Rolling back

Backups in `%LOCALAPPDATA%\pixelboard-tools\backup\`, all with the same
signature:

- `PixelBoard-1.3.3-installed.apk`: the original official 1.3.3.
- `PixelBoard-1.3.3-expressive-18.5.3.apk`: this fork's 1.3.3 build.

Going back from Gboard 18.6.3 to an 18.5.3 build is a downgrade
(`-d`), which Gboard may not handle cleanly; prefer fixing forward.

```bash
adb install -r -d "$LOCALAPPDATA/pixelboard-tools/backup/PixelBoard-1.3.3-expressive-18.5.3.apk"
```
