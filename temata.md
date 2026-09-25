# temata.md — Claudův poznámkový blok

Kontejner, ve kterém pracuju, se maže. Všechno, co si potřebuju pamatovat, píšu sem a pushuju.

## Pravidla od uživatele

- **Můj repo je jen `grrrrshadow/cota`.** Zapisovat (commit, push, release, issue, komentáře) smím jen tam.
- Ostatní repa (např. `brunodev85/winlator`, `brunodev85/winlator-app`, `brunodev85/vortek`, `brunodev85/gladio`) smím **jen číst**. Nikdy do nich nic nepsat.
- Uživatel píše česky. Otázky piš normálně textem do chatu (ne přes dialog s možnostmi) a pak skonči.
- `README.md` je schválně neutrální. Nepsat do něj, co děláme.
- **Podpisový klíč nikdy necommitovat** (repo je veřejné). Má ho uživatel a je v GitHub secretu `SIGNING_KEYSTORE_BASE64`. Pro lokální build si ho vyžádej od uživatele a ulož do `~/.android/debug.keystore` (alias `androiddebugkey`, hesla `android`). SHA-256 certifikátu: `0c:e6:ee:30:b4:e4:38:f3:b4:96:53:e7:0a:2a:a7:03:4e:76:bf:26:49:8e:f9:cc:7d:81:14:c0:49:63:b1:c0`.
- Uživatel nesmaže původní Winlator, dokud Winlator DL nebude fungovat na 100 %. Obě aplikace musí jít mít nainstalované současně.
- Soubory do chatu jdou jen do 30 MB. APK (~150 MB) patří do GitHub Releases (viz CI níže).

## Projekt: Winlator DL

Fork Winlatoru 11.2, který jde nainstalovat vedle originálu a má data disku C: v `Download/winlator/containerN`.

- Zdroj: `brunodev85/winlator@b6b2259`. Aplikace je submodul `brunodev85/winlator-app`, připnutý na `a030f55`. `vortek` a `gladio` jsou další submoduly (k sestavení APK nejsou potřeba).
- Release `w1` v cota = původní zip od uživatele (bez submodulů, složky `app`, `gladio`, `vortek` jsou prázdné).
- Commity: `fe83fcf` = nezměněný import winlator-app, `0674df8` = moje úpravy.
- Balíček `org.winlator`, název „Winlator DL“, `versionName 11.2-dl`, `versionCode 33`.

### Co je změněné (commit 0674df8)

- `applicationId` → `org.winlator`. Java namespace zůstal `com.winlator`, takže JNI jména se nemění.
- FileProvider authority → `${applicationId}.FileProvider` (stejná authority u dvou aplikací by zablokovala instalaci).
- Natvrdo zadané cesty v `cpp/*/include/{gladio,vortek,winlator}.h` → `/data/data/org.winlator/...`.
- `DataDirPatchOutputStream`: při rozbalování tzst/zip (`TarCompressorUtils.extract`, `ZipUtils.extract`) přepisuje `/data/data/com.winlator/` → `/data/data/org.winlator/` v obsahu souborů i v cílech symlinků. **Nový název balíčku musí mít přesně 12 znaků** (stejně jako `com.winlator`), jinak by se rozbily ELF binárky.
- `ContainerManager`: `.wine/drive_c` je symlink na `Download/winlator/containerN`. Při vytvoření kontejneru se tam rozbalí šablona. Když složka už existuje, soubory uživatele zůstanou. Duplikace zkopíruje i externí C:, **smazání kontejneru smaže i `containerN`**. V `Download/winlator` je `.nomedia`.
- `WineUtils.unixToDOSPath`: cesty do `Download/winlator/containerN` se mapují na `C:`, ne na `D:\winlator\...`.
- `build.gradle`: `google()` před `mavenCentral()` (Maven Central jinak vracel 429).

## Jak stavět

### CI (hlavní způsob)

- `.github/workflows/build-apk.yml`: při pushi do `main` (změny v `app/**`, gradle souborech nebo ve workflow) nebo ručně (`workflow_dispatch`) sestaví APK a vytvoří release `build-N` s `WinlatorDL-<verze>-buildN.apk`.
- Potřebuje secret `SIGNING_KEYSTORE_BASE64` (base64 keystoru). Bez něj build schválně selže, aby nevzniklo APK s jiným klíčem.
- Commit jen s poznámkami: přidej `[skip ci]` do zprávy (nebo měň jen soubory mimo sledované cesty).

### Lokálně

```
# SDK (cmdline-tools 11076708)
sdkmanager "platforms;android-35" "build-tools;34.0.0" "ndk;24.0.8215888" "cmake;3.22.1"
echo "sdk.dir=/home/user/android-sdk" > local.properties
bash gradlew --no-daemon --max-workers=2 assembleDebug   # při 429 z Maven Central zopakovat
# výsledek: app/build/outputs/apk/debug/app-debug.apk
```

- Gradle wrapper 8.14.5, AGP 8.4.2. Lokálně fungoval JDK 21, CI používá 17.
- Release assety stahuj `curl -L -C -` (proxy občas přeruší velký přenos, `-C -` naváže).
- Submoduly: `add_repo` (read) + `git clone`, připnuté SHA zjistíš přes `git ls-tree HEAD app vortek gladio` v klonu `brunodev85/winlator` (stačí `--filter=blob:none --no-checkout --depth 1`).
- Test přesunutého rootfs bez telefonu: rozbalit do `/data/data/org.winlator/files/rootfs`, přepsat cesty, spouštět přes `qemu-aarch64 <rootfs>/lib/ld-linux-aarch64.so.1 <binárka>`. Box64 přímo pod qemu nenaběhne (pevná adresa 0x34800000), přes ld.so ano: `Box64 arm64 v0.4.4 with Dynarec`.

## Technické poznatky o Winlatoru

- Rootfs: `/data/data/<pkg>/files/rootfs` (dřív `imagefs`, přejmenuje se samo). Kontejnery: `rootfs/home/xuser-N`, aktivní je symlink `home/xuser`.
- Disky: `c:` → `../drive_c`, `z:` → `../../../../` (kořen rootfs, systém Winlatoru, ne data uživatele), `D:` = `Download`, `E:` = `/data/data/<pkg>/storage`.
- Prebuilt binárky mají natvrdo `/data/data/com.winlator/files/rootfs`: 447 výskytů v rootfs (ELF interpreter, ld.so.cache, konfigurace), 20 v ovladačích a box64 (turnip ICD json, sokety vortek/gladio, interpreter box64). Obsahují je i komponenty stahované za běhu z `brunodev85/winlator/installable_components` (box64, turnip). Všechny výskyty mají přesně tvar `/data/data/com.winlator/`.
- `targetSdkVersion 28` = starý (legacy) přístup k úložišti. Wine proto vidí `/storage/emulated/0` přímo. Oprávnění k úložišti je povinné, bez něj se aplikace zavře.
- Úložiště v Androidu nerozlišuje velikost písmen: `Download/winlator` je stejná složka jako `Download/Winlator`, kam originál exportuje profily ovládání (`profiles/`). Nekoliduje to.
- `FileUtils.copy` přeskakuje symlinky, `FileUtils.delete` nenásleduje symlinky na složky.
- Šablona kontejneru (`container_pattern.tzst`) nemá v `drive_c` žádné symlinky. Vlastní verze Wine je při instalaci převádí na složky.

## OpenGL (co víme)

- **Gladio** (výchozí OpenGL ovladač): napevno **OpenGL 3.3, GLSL 3.30** (`gladio.h`, `GL_STRING_VERSION`).
- **Zink** (Mesa 22.2.5): `PIPE_CAP_GLSL_FEATURE_LEVEL` i `..._COMPATIBILITY` = 460, `PIPE_CAP_DOUBLES` = 1 (fp64 emuluje, Adreno ho hardwarově nemá). S **Turnipem na Adreno až OpenGL 4.6**, přesná verze se počítá za běhu podle Vulkanu. S Vortekem (ne-Adreno GPU) Winlator nastaví `MESA_GL_VERSION_OVERRIDE=3.3`.
- **VirGL** (23.1.9): verze přes `MESA_GL_VERSION_OVERRIDE`, v nastavení 2.0–4.0, výchozí 3.1.
- **Nejvyšší = OpenGL 4.6 přes Zink + Turnip, jen na Adreno.** Na telefonu uživatele neověřeno, jeho GPU zatím neznám.
- Vulkan: Turnip 26.2.0 (Adreno), Vortek 2.1 (ostatní). DXVK a VKD3D běží přes Vulkan.

## Stav a otevřené věci

- Winlator DL zatím nikdo nezkoušel na telefonu. Čekám na zpětnou vazbu uživatele.
- Neznámý telefon a GPU uživatele.
- Možná rizika: Wine s diskem C: na sdíleném úložišti (FUSE) může být pomalejší při startu her.
