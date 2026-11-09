# Fork notes (Brantley-Wyche/PixelBoard)

This fork adds the Material 3 Expressive look (upstream issue #133) on top of
Akshayykadam/PixelBoard. The design is in
`docs/superpowers/specs/2026-11-09-m3-expressive-look-design.md`.

## One-time setup (already done on this PC)

- Java 21 and jadx in `%LOCALAPPDATA%\pixelboard-tools\`.
- Stock Gboard bundle at `%LOCALAPPDATA%\pixelboard-tools\input\gboard-18.5.3.apkm`
  (APKMirror, `18.5.3.993608717-release-arm64-v8a`, Android 10+ variant).
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
pass its path to `build-fork.sh`, and add the new obfuscated name of the
top-rounded keyboard module's `applyOutline(KeyboardViewType, View)` method to
`GboardExpressiveLookPatch.kt` (in 18.5.3 it is `Lrbm;->s(Lqcl;Landroid/view/View;)V`;
find it by the `top_rounded_keyboard` / `always_top_rounded_keyboard_for_debug`
flag strings). Until then the corners still work and the other two effects
are simply skipped.

## Rolling back

A copy of the original 1.3.3 install is in
`%LOCALAPPDATA%\pixelboard-tools\backup\`. It has the same signature:

```bash
adb install -r -d "$LOCALAPPDATA/pixelboard-tools/backup/PixelBoard-1.3.3-installed.apk"
```
