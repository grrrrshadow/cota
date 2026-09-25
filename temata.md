# temata.md — Claudův poznámkový blok

Kontejner, ve kterém pracuju, se maže. Všechno, co si potřebuju pamatovat, píšu sem a pushuju.

## Pravidla od uživatele

- **Můj repo je jen `grrrrshadow/cota`.** Zapisovat (commit, push, release, issue, komentáře) smím jen tam.
- Ostatní repa (např. `brunodev85/winlator`, `brunodev85/winlator-app`, `brunodev85/vortek`, `brunodev85/gladio`) smím **jen číst**. Nikdy do nich nic nepsat.
- Uživatel píše česky. Otázky piš normálně textem do chatu (ne přes dialog s možnostmi) a pak skonči.
- `README.md` je schválně neutrální. Nepsat do něj, co děláme.
- **Podpisový klíč je v GitHub secretu `SIGNING_KEYSTORE_BASE64`** (uživatel ho tam nahrál 25. 9. 2026, má ho i u sebe v souboru „temata s klíčem“). CI ho použije, když existuje; jinak podepíše dočasným klíčem. Uživatel stejně před každou novou verzí aplikaci odinstaluje. **Žádný klíč nikdy necommitovat, repo je veřejné.** Pro lokální build si klíč vyžádej od uživatele a ulož do `~/.android/debug.keystore` (alias `androiddebugkey`, hesla `android`).
- Uživatel nesmaže původní Winlator, dokud Winlator DL nebude fungovat na 100 %. Obě aplikace musí jít mít nainstalované současně.
- Soubory do chatu jdou jen do 30 MB. APK (~150 MB) patří do GitHub Releases (viz CI níže).
- **Dělej jen to, co uživatel chce, nic navíc.** Když řekne „odstraň hlášku“, odstraň hlášku, neměň chování okolo. **Exit je Exit:** zabije všechno a nic dalšího nedělá (žádné „slušné zavírání“ programů). **Nic nespouštět automaticky.** Odpovídej krátce.

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

- `.github/workflows/build-apk.yml`: při pushi do `main` (změny v `app/**`, gradle souborech nebo ve workflow) nebo ručně (`workflow_dispatch`) sestaví APK a vytvoří release `build-N` s `WinlatorDL-<verze>-buildN.apk`. Uživatel si APK stahuje z GitHub Releases (do chatu se 150 MB nevejde, do repa jako soubor taky ne, limit 100 MB).
- Secret `SIGNING_KEYSTORE_BASE64` je volitelný (viz výše). Opakované spuštění stejného běhu jen nahradí APK v existujícím release. Build 1 (25. 9. 2026) vznikl ještě bez klíče.
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
- **VirGL**: host = starý fork virglrenderer přímo v aplikaci (`cpp/virglrenderer`, `host_feature_check_version = 3`, `vrend_renderer.c:6990`), guest = Mesa 23.1.9 „virpipe“ v rootfs. Host vytváří GLES 3 kontext a podle skutečné verze GLES na telefonu nastaví GLSL úroveň: GLES 3.0 → 130, 3.1 → 310, 3.2 (tesselace+geometry+gpu_shader5) → 400, +separate shader objects → 410, +compute → **430** (`vrend_fill_caps_glsl_version`). Protože check version < 6, guest Mesa ořízne **kompatibilní (legacy) kontexty na GLSL 1.40 = OpenGL 3.1** (`virgl_screen.c:175`). Wine a většina her tvoří legacy kontext. `MESA_GL_VERSION_OVERRIDE=4.0` (nastavení v dialogu) jen přepíše číslo verze; Mesa pak výpočet verze úplně přeskočí (`_mesa_compute_version` → `if (ctx->Version) goto done`), takže kompilátor GLSL zůstane na core úrovni hosta (430 na GLES 3.2), ale rozšíření vázaná na GLSL kompat úroveň (např. ARB_gpu_shader5) se nezapnou. Přípona `FC` (např. `4.3FC`) vynutí core profil → guest použije plnou GLSL úroveň (bez legacy funkcí glBegin/glEnd atd.). Volba driconf `allow_higher_compat_version=true` jde nastavit jako env proměnná (Mesa čte env se jménem volby, `xmlconfig.c:386`; stejně už funguje `mesa_glthread=true`), ale rozšíření dál gatuje kompat úroveň, takže moc nepomůže.
- Ve Winlatoru DL (od 25. 9. 2026) má VirGL dialog verze až 4.6 a checkbox „Core profile“ (= přípona FC). Env proměnná `MESA_GL_VERSION_OVERRIDE` z nastavení kontejneru se nepoužije, dialog ji přepíše (`XServerDisplayActivity` řádek ~513 vs ~769).
- Vulkan: Turnip 26.2.0 (Adreno), Vortek 2.1 (ostatní). DXVK a VKD3D běží přes Vulkan.
- **Telefony uživatele:** cílový je **Motorola Edge 60 Fusion** (Android 15, Dimensity 7400 nebo 7300 — neověřeno, GPU **Mali-G615 MC2**, GLES 3.2, Vulkan 1.3), teď v servisu; zatím zkouší na **Oppo A18** (Android 14, Helio G85, Mali-G52 MC2, GLES 3.2, slabý). Třetí telefon „Motorola m388“ — model nejasný. Všechny MediaTek/Mali → Turnip nejde, Zink jen přes Vortek (ořezaný na 3.3, od buildu 5 přebitelný `real`). Uživateli funguje **VirGL s nastavením 4.0**. Nejvyšší reálná úroveň na Mali je tedy VirGL ~4.3 (core), Gladio 3.3.

## Z poznámek druhého chatu (OpenTTD Decouple, repo forclaude; uživatel je poslal 25. 9. 2026)

- Tamní cíl: Winlator, který se nainstaluje, **rovnou spustí OpenTTD Decouple** a má **přednastavené čudlíky** hráče (L CTRL, L ALT+C, MSU, MLB, MSD). Decouple má config vedle exe jako `openttdDecouple.cfg`. OpenTTD stačí OpenGL 3.x. Přibalit smí OpenTTD/Decouple (GPL-2) a OpenGFX/OpenSFX/OpenMSX, **originální grafiku TTD ne**. Zatím jen nápad, nic z toho v cota není.
- **Licence Winlatoru je LGPL-2.1** (soubor LICENSE ve winlator i winlator-app), ne GPL-3.0, jak tvrdí tamní poznámka. Upravený zdroják musí být veřejný — je v cota.
- Morrowind (DX8) ve Winlatoru na Edge nenaběhl. Náš fork má `dxwrapper/d8vk-1.0` (DX8 přes Vulkan), dá se zkusit. Lepší cesta je nativní OpenMW for Android; multiplayer TES3MP (Android klient zřejmě neexistuje).
- Skyrim na Mali prakticky ne (DXVK na Mali slabé); na to je potřeba Snapdragon/Adreno.

## Výsledky kontroly změn (25. 9. 2026)

- Opraveno: otevření souboru z C: (html, pdf, txt) přes FileProvider padalo, protože `file_paths.xml` znal jen interní úložiště → přidán `external-path`.
- Opraveno: logy obou aplikací šly do `Documents/Winlator/logs.txt` a navzájem se mazaly → Winlator DL loguje do `Documents/WinlatorDL`.
- Opraveno: neúspěšná duplikace kontejneru nechala v `Download/winlator/containerN` částečnou kopii → smaže ji, pokud ji sama vytvořila.
- **Známé omezení:** obě aplikace používají stejné pevné UDP porty na 127.0.0.1 (WinHandler 7946/7947, winebus 7949, MIDI 7950). **Nespouštět kontejner v obou aplikacích současně**, jinak si kradou ovladač, správce úloh a MIDI. Neřešeno.
- Známé omezení: přejmenování jen změnou velikosti písmen (např. `hra` → `Hra`) na C: nic neudělá, sdílené úložiště nerozlišuje velikost písmen. Neřešeno.
- Po odinstalaci zůstane `Download/winlator/containerN`. Nový kontejner se stejným číslem data převezme (soubory uživatele zůstanou, systémové soubory Windows se přepíšou šablonou). Záměr: hry přežijí přeinstalaci. Když chce uživatel čistý start, musí složku smazat ručně.

- **CI podepisovalo buildy 2–5 náhodným klíčem**, ne klíčem ze secretu: runner má nastavené `XDG_CONFIG_HOME` a AGP pak hledá debug keystore tam. Oprava: `ANDROID_USER_HOME=$HOME/.android` v `GITHUB_ENV` a krok „Check signing key“, který build zastaví, když otisk APK nesedí s klíčem ze secretu. Ověřeno stažením buildu 5 (`caf2d9…` místo `0ce6ee…`).

## Hláška PortableApps „did not close properly“ (25. 9. 2026)

- Hlásí ji spouštěč PortableApps.com (např. `D:\0exewin\TotalCommanderPortable\TotalCommanderPortable.exe`), ne Windows ani Wine. Za běhu si drží `Data\PortableApps.comLauncherRuntimeData-<AppID>.ini`. Když Winlator při Exit programy zabije, soubor zůstane a příští start jen ukáže hlášku a skončí.
- Řešení (přání uživatele: jen odstranit hlášku, Exit neměnit): `core/PortableAppsLauncherState.removeStale()` při startu kontejneru (`setupWineSystemFiles`) projde C: a všechny disky do hloubky 2 složek a smaže tyhle soubory ze složek `Data`. Úklid spouštěče (obnova registru apod.) se tím přeskočí, uživatel to tak chce.

## Průzkum OpenGL v kódu (25. 9. 2026)

- **Gladio**: vzdálené volání GL přes sdílenou paměť do rendereru v aplikaci na GLES 3 kontextu. Prakticky vyžaduje GLES 3.2 (shadery vždy `#version 320 es`). Hlásí verzi 3.3, ale limity pod minimem GL 3.3 (8 texturovacích jednotek, max textura 4096, 4 světla). 163 příkazů na straně rendereru je jen „not implemented“ (display listy, clip planes, glTexGen, glDispatchCompute…), wireframe (`glPolygonMode GL_LINE`) kreslí špatně. DXT textury rozbaluje na CPU. Žádný vsync ani nastavení v UI. GLX odmítne kontext > 3.3.
  - Levné zlepšení: nastavitelná verze přes env proměnnou (jen pro hry, co kontrolují číslo), reálné limity z telefonu, opravit wireframe, dialog s nastavením Gladia. Velké: zpřístupnit GLES 3.1/3.2 compute/SSBO/tesselaci jako GL 4.x (týdny).
- **Zink 22.2.5**: stará verze (xlib GLX, každý snímek jde přes CPU a XShmPutImage). Neumí nic z moderních rozšíření, která Turnip 26.2 má. Novější Zink pro glibc rootfs zatím nikdo nevydal, ale jde postavit na Ubuntu ≤ 24.04 (rootfs má GLIBC 2.39). S Vortekem (Mali) je natvrdo `MESA_GL_VERSION_OVERRIDE=3.3`; Vortek pro Zink vypíná některá rozšíření a propouští jen 55 vybraných.
  - Pro Mali (telefony uživatele) nejvíc slibuje zkusit, co Zink přes Vortek na Mali-G615 reálně nahlásí. **Od buildu 5:** proměnná `MESA_GL_VERSION_OVERRIDE` z nastavení kontejneru má u Zinku přednost před pevným 3.3; hodnota `real` ji úplně odstraní, takže Zink nahlásí skutečnou verzi (`XServerDisplayActivity`, větev `GraphicsDrivers.ZINK`).
- Neprozkoumáno (došly kredity): hlubší VirGL (host vs. nový virglrenderer), strana Wine a her, jiné forky Winlatoru se zdroji.

## Stav a otevřené věci

- Winlator DL zatím nikdo nezkoušel na telefonu. Čekám na zpětnou vazbu uživatele.
- Neznámý telefon a GPU uživatele.
- Možná rizika: Wine s diskem C: na sdíleném úložišti (FUSE) může být pomalejší při startu her.
