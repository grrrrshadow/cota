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

- `.github/workflows/build-apk.yml`: při pushi do `main` (změny v `app/**`, gradle souborech nebo ve workflow) nebo ručně (`workflow_dispatch`) sestaví APK. **APK je artefakt běhu Actions** (`actions/upload-artifact`, jméno `WinlatorDL-buildN`), ne Release. Uživatel si ho najde na stránce běhu v záložce Actions dole v „Artifacts“. Přání uživatele: nechat to jako artefakty, ne dělat Releases. (Artefakt je zip, retence 90 dní.)
- Secret `SIGNING_KEYSTORE_BASE64` je volitelný (viz výše). Build 1 (25. 9. 2026) vznikl ještě bez klíče. Releases build-1 až build-7 zůstaly z dřívějška, novější buildy už jdou jen do artefaktů.
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

- Hlásí ji spouštěč PortableApps.com (`TotalCommanderPortable.exe`, titulek okna „… (PortableApps.com Launcher)“), ne Windows ani Total Commander. Text je v `PortableApps.comLauncher.nsi:428` (`MessageBox MB_ICONSTOP $(LauncherCrashCleanup)`), před ním se nečte žádný klíč registru.
- Uživatel chce hlášku **odstranit v kódu**, ne obcházet. Mazání ini ve Winlatoru i „slušné zavírání“ při Exit jsem na jeho přání vyhodil. Řešení: `tools/portableapps-launcher/` = patch spouštěče (zbylý runtime ini smaže a program normálně spustí) + `build.sh` (makensis na Linuxu, chybějící jazykové soubory z přibaleného NSIS převedené z cp1252 na UTF-8). Uživatel nahradí `D:\0exewin\TotalCommanderPortable\TotalCommanderPortable.exe`.
- Uživatel tvrdí, že to na Windows vypínal v registru (HKLM). V kódu spouštěče žádný takový klíč není; nejspíš šlo o Windows Error Reporting (`DontShowUI`), to je jiná hláška. Kdyby řekl konkrétní klíč, nastavit ho v kontejneru je snadné.

## Průzkum OpenGL v kódu (25. 9. 2026)

- **Gladio**: vzdálené volání GL přes sdílenou paměť do rendereru v aplikaci na GLES 3 kontextu. Prakticky vyžaduje GLES 3.2 (shadery vždy `#version 320 es`). Hlásí verzi 3.3, ale limity pod minimem GL 3.3 (8 texturovacích jednotek, max textura 4096, 4 světla). 163 příkazů na straně rendereru je jen „not implemented“ (display listy, clip planes, glTexGen, glDispatchCompute…), wireframe (`glPolygonMode GL_LINE`) kreslí špatně. DXT textury rozbaluje na CPU. Žádný vsync ani nastavení v UI. GLX odmítne kontext > 3.3.
  - Levné zlepšení: nastavitelná verze přes env proměnnou (jen pro hry, co kontrolují číslo), reálné limity z telefonu, opravit wireframe, dialog s nastavením Gladia. Velké: zpřístupnit GLES 3.1/3.2 compute/SSBO/tesselaci jako GL 4.x (týdny).
- **Zink 22.2.5**: stará verze (xlib GLX, každý snímek jde přes CPU a XShmPutImage). Neumí nic z moderních rozšíření, která Turnip 26.2 má. Novější Zink pro glibc rootfs zatím nikdo nevydal, ale jde postavit na Ubuntu ≤ 24.04 (rootfs má GLIBC 2.39). S Vortekem (Mali) je natvrdo `MESA_GL_VERSION_OVERRIDE=3.3`; Vortek pro Zink vypíná některá rozšíření a propouští jen 55 vybraných.
  - Pro Mali (telefony uživatele) nejvíc slibuje zkusit, co Zink přes Vortek na Mali-G615 reálně nahlásí. **Od buildu 5:** proměnná `MESA_GL_VERSION_OVERRIDE` z nastavení kontejneru má u Zinku přednost před pevným 3.3; hodnota `real` ji úplně odstraní, takže Zink nahlásí skutečnou verzi (`XServerDisplayActivity`, větev `GraphicsDrivers.ZINK`).
- Neprozkoumáno (došly kredity): hlubší VirGL (host vs. nový virglrenderer), strana Wine a her, jiné forky Winlatoru se zdroji.

## Total Commander pro Android, A7Zip, instalace (5. 10. 2026)

- Uživatel poslal `a7zip21src.zip` s tím, že chce v TC: pryč boční rychlo-posuvník (s vypínačem v nastavení), AMOLED černé pozadí, jemně šedé písmo, odblokovat instalaci APK. **A7Zip je jen archivační plugin TC** (`org.ghisler.a7zip`, `org.ghisler.tcpacker.PluginService`, 7zip přes JNI) + testovací obrazovka se 4 tlačítky. Žádný seznam souborů, posuvník, motiv ani instalace. **Samotný TC pro Android je closed source**, zdroják nikde není → ty úpravy udělat nejde. Uživatel to ví.
- Instalace APK: Google v 2022 donutil Ghislera vyhodit instalaci APK z verze na Play. **Neomezená verze** je v Ghislerově F-Droid repu: `https://raw.githubusercontent.com/chrisgch/tca/master/fdroid/repo`, fingerprint `3576596CECDD70488D61CFD90799A49B7FFD26A81A8FEF1BADEC88D069FA72C1`. Soubor `tcandroid-down362d-universal.apk` (3.62d, univerzální, má `REQUEST_INSTALL_PACKAGES`). Uživateli poslán. Uživatel nechce APKPure (přebaluje), F-Droid/oficiální zdroj je OK.
- Otevřené: vypínač bočního posuvníku a vlastní barvy jsou možná v nastavení TC (Configuration) — neověřeno, nápovědu jsem neprošel.
- Instalace Winlator DL: Android hlásí „aplikace byla vytvořena pro starší verzi systému“ (targetSdk 28). Je to správně, **targetSdk zvedat nesmím** (novější target zakazuje spouštět binárky z dat aplikace → Wine/Box64 by nešel). Uživatel ví, že má dát „Přesto nainstalovat“.

## Stav a otevřené věci

- Uživatel teď Winlator DL zkouší na **Redmi A5** (3 GB RAM, slabý; náhrada za Edge 60 Fusion v servisu cca 2 měsíce). Cílový telefon zůstává Edge 60 Fusion. Čekám na zpětnou vazbu, **nic nebuildit, dokud neřekne**.
- Možná rizika: Wine s diskem C: na sdíleném úložišti (FUSE) může být pomalejší při startu her.

## Vzkaz z chatu OpenTTD Decouple (13. 10. 2026): OpenTTD a OpenGL ve Winlatoru

Psal to druhý chat (repo `grrrrshadow/forclaude`, PR #1). Uživatel chce hru nechat ve vanilla stavu OpenGL a dělat lepší OpenGL ve Winlatoru; zatím hraje s **VirGL 4.0**, se kterým hra naběhne.

### Co se dělo a co z toho bylo naše

- Kontejner uživatele: 1280x720, Vulkan **Vortek**, OpenGL **Gladio** (výchozí), DXVK, VKD3D, ALSA. Hra bez nastavení bere 75 % obrazovky = okno **960x540**, blitter **40bpp-anim**, driver **win32-opengl**.
- První start po založení `openttdDecouple.cfg`: černé okno, po 7 až 22 s EXCEPTION_ACCESS_VIOLATION, prázdný stacktrace, `.dmp` 0 bajtů. Hra si před zkouškou OpenGL zapíše `hwaccel.dat` a smaže ho po prvním snímku; druhý start ho najde, ukáže „GPU driver crashed the game. Hardware acceleration disabled“, zapíše `video_hw_accel = false` do cfg a jede na GDI. Proto „napodruhé vždy najede“. Znovu zkusit OpenGL: `video_hw_accel = true` v cfg.
- **Byla to (aspoň zčásti) chyba hry, ne Gladia:** náš Decouple má úroveň zoomu 16x, a když je vypnutá, načítání spritů jí nechá jen velikost bez pixelů. Vanilla OpenGL kód kurzoru (`OpenGLSprite`) ty pixely čte → segfault při **prvním snímku** (před prvním `Paint`, proto černé okno a `hwaccel.dat` zůstal). Reprodukováno na Linuxu s Mesa llvmpipe, opraveno v forclaude (commit „OpenGL: the cursor sprites crashed the game…“). Vanilla OpenTTD tuhle chybu nemá; co psala vanilla na Gladiu, nevíme přesně.
- Nová verze hry píše do crash logu `crash.trace` (posledních 48 značek s časem od startu: okno, OpenGL kontext, velikost, sprity, první snímek...) a `os.wine` verzi Wine. Příští `crash*.json.log` ze složky hry řekne, u kterého kroku to umřelo.

### Co OpenTTD od OpenGL chce (`openttd/src/video/opengl.cpp`, `win32_v.cpp`, `table/opengl_shader.h`) — pro lepší OpenGL ve Winlatoru

- **Kontext:** `wglCreateContextAttribsARB` nejdřív 4.5 core profile, když null tak 3.2 core profile, jinak starý `wglCreateContext`. S `-d driver=8` navíc debug kontext a `glDebugMessageCallback(ARB)`.
- **Verze:** na Windows odmítne cokoli pod **3.2** (spadne na GDI, nepadá). Renderer string začínající `llvmpipe`/`softpipe` odmítne. Rozšíření čte přes `glGetStringi(GL_EXTENSIONS, i)`.
- **Nutné:** NPOT textury, `GL_R8`/`GL_RED` textury (ARB_texture_rg), VBO, **PBO** (`GL_PIXEL_UNPACK_BUFFER`) + `glMapBuffer(GL_READ_WRITE)`/`glUnmapBuffer`, VAO, shadery, `glBindFragDataLocation` (u 3.2 nutné). `GL_MAX_TEXTURE_SIZE` ≥ větší rozměr okna, `GL_MAX_TEXTURE_IMAGE_UNITS` ≥ 4.
- **Když je driver nahlásí, hra je použije (Gladio hlásí obě):** `GL_ARB_buffer_storage` → `glBufferStorage(... PERSISTENT | COHERENT | CLIENT_STORAGE)` + trvale namapovaný `glMapBufferRange` + `GL_ARB_sync` (`glFenceSync`/`glClientWaitSync`/`glDeleteSync`). `GL_ARB_clear_buffer_object` → `glClearBufferSubData` (Gladio nehlásí, hra místo toho PBO namapuje a vyplní). **Nahlášené a nedodělané rozšíření = pád nebo rozbitý obraz.**
- **Textury:** obraz `GL_TEXTURE_2D` `GL_RGBA8` velikosti okna, nahrávání **`GL_BGRA` + `GL_UNSIGNED_INT_8_8_8_8_REV`** s `GL_UNPACK_ROW_LENGTH = pitch`, data z PBO (ukazatel = offset), každý snímek `glTexSubImage2D` špinavého obdélníku. Blitter 8bpp: `GL_R8`/`GL_RED`/`GL_UNSIGNED_BYTE`. Blitter **40bpp-anim** (výchozí s 32bpp sadou): druhá textura `GL_R8` (animační vrstva) + druhé PBO. **Paleta: `GL_TEXTURE_1D`** `GL_RGBA8` 256 přes `glTexImage1D`/`glTexSubImage1D`, v shaderu `sampler1D`. Kurzor: `GL_TEXTURE_2D` s mip úrovní na každý zoom (`glTexImage2D` level i, `GL_TEXTURE_MAX_LEVEL`), 1x1 náhradní textury.
- **Shadery:** při 3.2+ GLSL **`#version 150`** (`in`/`out`, `out vec4 colour` + `glBindFragDataLocation`), jinak `#version 110`. Samplery 2D i **1D**, `textureLod`. Kreslí `glBindVertexArray` + `glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)`, `glClear`, `glBlendFunc`, `glActiveTexture` 0..3.

### Co jsem viděl v Gladiu (zdroj `app/src/main/cpp/gladiorenderer`, guest `libGL.so.1.7.0` v `gladio-1.1.tzst`)

- Guest exportuje všechno, co hra volá (`glTexImage1D`, `glMapBuffer`, `glMapBufferRange`, `glBufferStorage`, `glGetStringi`, `glFenceSync`, `glClientWaitSync`, `glBindFragDataLocation`...). `shader_converter.c` mapuje `sampler1D` → `sampler2D`. Host hlásí 3.3 / GLSL 3.30 a v `gl_context.c:536` i `GL_ARB_buffer_storage`, `GL_ARB_sync`, `GL_ARB_map_buffer_range`.
- **`sizeofGLType()` v `gladio.h` nezná `GL_UNSIGNED_INT_8_8_8_8_REV`** (vrací 0) → nahrání textur hry může skončit prázdné (černý nebo nesmyslný obraz) i bez pádu. Na little-endianu je `GL_BGRA` + `8_8_8_8_REV` totéž co `GL_BGRA` + `GL_UNSIGNED_BYTE`, stačí to v tabulce typů přidat jako 4 bajty.
- PBO host emuluje: `glBufferStorage` pro `GL_PIXEL_UNPACK_BUFFER` jen alokuje ashmem (fd guestovi), `glTexSubImage2D` pak čte `pixelUnpackBuffer->mappedData + offset`. Zda guest umí `glMapBuffer(GL_READ_WRITE)` a trvalé `glMapBufferRange` nad tím, jsem z binárky nepoznal — to je první věc k ověření, když hra na Gladiu bude kreslit špatně.
- Limity Gladia hře stačí (8 texturovacích jednotek, max textura 4096).

### Jak to zkoušet

- `openttd.exe -v win32-opengl -d driver=8` (debug kontext, GL hlášky do konzole/logu), `-b 32bpp-optimized` (bez animační textury), `-v win32` = GDI pro srovnání. V cfg `video_hw_accel = true`, jinak hra OpenGL vůbec nezkusí.
- Nejdřív nový build hry (s opravou kurzoru): na Gladiu se uvidí, jestli zbyl ještě nějaký problém Gladia; na VirGL 4.0 hra jede.

## 17. 10. 2026: VirGL se v DL neukládá, OpenTTD akcelerace, nástroje pro Linux

- **Hlavní chyba:** ve Winlatoru DL se nastavení VirGL (verze, Core profile) **neuloží ani po fajfce Save ✓** (uživatel ověřil, fajfka je dole). Po návratu je vždy 3.1 bez Core. Proto všechny pokusy s OpenTTD v DL ve skutečnosti běžely na 3.1 a OpenTTD (Windows) chce **≥ 3.2** (`openttd/src/video/opengl.cpp:580`) → „nebyla nalezena kompatibilní grafická karta“. Na původním Winlatoru 10.1 se VirGL 4.0 drží a hra s akcelerací jede (viz vzkaz z 13. 10. výše).
- Statická kontrola nenašla chybu: `GraphicsDriverPicker`, `VirGLConfigDialog` (OK → `anchor.setTag`), `ContainerDetailFragment` (BTConfirm → `setGraphicsDriverConfig` + `saveData`), `Container.saveData/loadData`, `GraphicsDrivers.parseConfigs` (dělí podle prvního `|`), `KeyValueSet`, `Shortcut` extra data (dělí podle prvního `=`) — vše vypadá správně; JVM test `KeyValueSet`+`parseConfigs` prošel. Vortek už spojuje rozšíření `:` (ne `|`). → **Je potřeba dynamický test:** `app/src/test/java/com/winlator/VirGLConfigPersistTest.java` (Robolectric) projde skutečné UI: picker → dialog VirGL → 4.6 + Core → OK → uložit kontejner → načíst → znovu otevřít dialog. Stav: napsaný, zatím nespuštěný.
- Uživatel chce **skutečnou příčinu, ne kosmetiku** („musí to fungovat“). Hra má zůstat vanilla v OpenGL, lepší má být Winlator.
- Gladio podle druhého chatu: `sizeofGLType()` v `gladio.h` nezná `GL_UNSIGNED_INT_8_8_8_8_REV` (OpenTTD jím nahrává textury) → přidat jako 4 bajty. Gladio GLX hlásí jen `GLX_ARB_create_context` (bez `_profile`).
- Podezření k VirGL bez Core: legacy kontext má v guest Mesa GLSL max 1.40, OpenTTD při ≥3.2 kompiluje shadery `#version 150` → bez Core by to mohlo selhat i při čísle 3.3; s Core (FC) dostane hostovu úroveň (430 na GLES 3.2). Ověřuje workflow `openttd-hwaccel-winlator`.

### ✅ Příčina nalezena a opravena (17. 10. 2026)

- **Chyba původního Winlatoru 11.2** (ne našich úprav): `GraphicsDriverPicker.getGraphicsDriverConfig()` přidával oddělovač `|` jen když `length() > 0`. Když je nastavení Vulkanu (Vortek/Turnip) prázdné (dialog Vorteku nikdo neotevřel), uložilo se `glVersion=4.6,...` **bez `|`**. `GraphicsDrivers.parseConfigs()` pak pro `vortek,virgl` nepozná, ke kterému API text patří, a vrátí obě prázdná → VirGL zpět na 3.1 bez Core. Stejná chyba v `DXWrapperPicker.getDXWrapperConfig()` (VKD3D feature level se ztrácel, když DXVK nastavení prázdné). Oprava: `if (i > 0) append('|')`. Robolectric test `VirGLConfigPersistTest` (2 testy) ji předtím zopakoval (`expected 4.6 but was 3.1`) a teď prochází.
- Původní Winlator 10.1 ukládal nastavení jinak (bez dvojice Vulkan|OpenGL), proto tam VirGL 4.0 drží.
- Kontejnery uložené starým buildem mají nastavení už rozbité → po instalaci nového buildu VirGL nastavit **jednou znovu** a uložit ✓.
- **Oprava tvrzení:** „na původním Winlatoru s VirGL 4.0 hra jede“ **neznamená, že jede s akcelerací.** OpenTTD po prvním neúspěchu zapíše do `openttdDecouple.cfg` `video_hw_accel = false` a dál jede na GDI bez chybové hlášky (uživatel na to upozornil). **Testovací postup:** před každým pokusem dát v cfg `video_hw_accel = true` (nebo zaškrtnout Hardware acceleration ve hře a restartovat) a po startu zkontrolovat, jestli je pořád zapnutá.
- 17. 10.: uživatel má **zpátky Motorolu Edge 60 Fusion** ze servisu (+ „novou verzi Winlatoru“ — nevím kterou).

### Ověřeno na telefonu + výsledek rozboru OpenTTD (17. 10. 2026)

- **Na Motorole s buildem 10: VirGL + Core → OpenTTD akceleraci zapnul a drží.** První pokus spadl (hra i Winlator), po znovu-zapnutí akcelerace a restartu hry to jelo. Trhání hry zůstalo — uživatel: je to vlastnost hry (Decouple), trhala se už předtím; akcelerace řeší jen kreslení, simulace jede na CPU přes Box64.
- Rozbor (workflow `openttd-hwaccel-winlator`, ověřeno druhým agentem): Wine 10.10 (z `ntdll.so`), Box64 0.4.4. Wine vždy nabízí `WGL_ARB_create_context(_profile)`, OpenTTD si sám řekne o core 4.5, pak core 3.2, pak legacy. Wine hledá GL funkce nejdřív přes `dlsym`, takže omezené `glXGetProcAddressARB` Gladia nevadí.
- **VirGL:** libGL = Mesa 23.1.9 gallium **xlib** GLX. `MESA_GL_VERSION_OVERRIDE` nastaví `ctx->Version` pro všechny profily; Mesa pak odmítne žádosti nad tím číslem (`st_manager.c:1031-1037`). 3.1 → obě core žádosti odmítnuty → legacy 3.1 → OpenTTD `opengl.cpp:580`. **3.2–4.3 funguje, Core NENÍ potřeba** (moje dřívější teorie o GLSL 1.40 pro OpenTTD neplatí — ten strop se týká jen aplikací, co tvoří legacy kontext). **4.4+ nedávat:** OpenTTD podle čísla zapne persistent mapping (`opengl.cpp:607`), virgl (hfcv 3) nemá ARB_buffer_storage → `glMapBufferRange` vrátí NULL → „Can't get pointer to screen buffer“. Pokud host hlásí GLSL 310 (ne 430), Mesa vrátí verzi GLSL 0 → pak `MESA_GLSL_VERSION_OVERRIDE=330`.
- **Zástupce hry má vlastní `graphicsDriverConfig`** a env proměnné, které přebíjí kontejner (`XServerDisplayActivity.java:213, 514`).
- **Gladio:** podle kódu projde všemi kontrolami Init; selhání až později (nejspíš link shaderů `#version 320 es` na Mali, + `GL_UNSIGNED_INT_8_8_8_8_REV` v `sizeofGLType`). Potřeba chybový text z telefonu: `openttd.exe -v win32-opengl` (MessageBox s důvodem, pak hra skončí) nebo `-d driver=2`.
- **Zink + Vortek:** na papíře projde (3.3 Core, GLSL 4.60, renderer „zink (Vortek…)“). Riziko: Mali Vulkan musí mít `VK_KHR_timeline_semaphore`; jinak není GL vůbec (zink 22.2.5 nespadne na softpipe). Při rozbitém obraze `MESA_EXTENSION_OVERRIDE=-GL_ARB_buffer_storage`.
- **Moje chyba z buildu 5:** větev Zinku v `XServerDisplayActivity` (~762) čte `MESA_GL_VERSION_OVERRIDE` dřív, než se sloučí proměnné kontejneru (~513), takže uživatelskou hodnotu nikdy nevidí; hodnota kontejneru pak 3.3 stejně přepíše. `real` „funguje“ jen proto, že ho Mesa odmítne jako neplatné. Uklidit (nebo vrátit), až bude čas.
- Navrženo uživateli (čeká na souhlas): výchozí VirGL 3.1 → **3.3** + varování u 4.4+.
- **Brunovi zatím NIC neposílat** (rozhodnutí uživatele 17. 10.): až uživatel uvidí měřitelný rozdíl mezi zapnutou a vypnutou HW akcelerací, bude si jistý, že Bruna neotravuje s něčím nefunkčním. Nejdřív důkladnější testy. Kdyby se PR dělal: fork pod `grrrrshadow`, větev jen s opravou `|` (2 řádky v `GraphicsDriverPicker` + `DXWrapperPicker`) na Brunově nejnovějším `main`, nic z našich úprav.
- **Box64 (17. 10.):** DL má **Box64 0.4.4 (tag 2. 8. 2026, build 3. 8. 2026) = nejnovější upstream**, bez Winlator patchů, bez BOX32, DynaCache zakompilovaný. Winlator natvrdo nastavuje `BOX64_DYNACACHE=0` (`GuestProgramLauncherComponent.java:143-146`), ale proměnné kontejneru/zástupce se aplikují později a přebijí to → uživatel může zkusit `BOX64_DYNACACHE=1`, `BOX64_DYNACACHE_LIMIT=512`. Preset PERFORMANCE = SAFEFLAGS 1, FASTNAN 1, FASTROUND 1, X87DOUBLE 0, BIGBLOCK 3, STRONGMEM 0, FORWARD 512, CALLRET 1, WAIT 1, NATIVEFLAGS 1, WEAKBARRIER 2 — už skoro nejrychlejší. CPU affinity se nastavuje až při prvním okně přes WinHandler (default všechna jádra). rc soubor `/etc/config.box64rc` se přepisuje při každém startu; `~/.box64rc` přetrvá. Wine má jen nový WoW64 → OpenTTD jede jako čisté x86_64. (Workflow `box64-improvements`.)

### Nástroje (uživatel: „vyvíjej v Linuxu a ulož si to do repa, ať nebuildíš pokaždé APK na pokusy“)

- `tools/dev-setup.sh [SDK_DIR]` — po vymazání kontejneru nainstaluje SDK/NDK/CMake a zapíše `local.properties`.
- `tools/run-tests.sh [--tests '*VirGL*']` — pustí JVM testy (`testDebugUnitTest`, Robolectric) bez stavění APK; log v `build/test-run.log`.
- Testy jsou v `app/src/test/`. CI (`build-apk.yml`) změny jen v `app/src/test/**` ignoruje. Commity s nástroji/poznámkami dávej s `[skip ci]`.

### CET / CoinEx (uživatel se ptal 17. 10.)

- CoinEx končí (oznámení 2026-09): ne-spot 22. 9., spot 29. 9., **výběry do 22. 12. 2026 02:00 UTC**; CET na účtech automaticky odkoupeno za **0,005 USDT/CET** 29. 9.; CSC + OneSwap ukončeny 29. 9.; oficiální RPC `rpc.coinex.net` 17. 10. vrací 504.
- Uživatel píše, že mu CET „nuceně prodali za 0,0000005“ — to je 10 000× méně než oficiální cena. Doporučeno zkontrolovat historii aktiv (kolik USDT připsáno) a případně podporu do 22. 12. Výsledek průzkumu: CET na CSC (většina, ~2,44 mld.) je prakticky zmrazené — poslední blok 55 348 421 (29. 9. 03:16 UTC), 52 z 54 validátorů vedeno pod CoinExem (~99 % stake), pokračovat jde jen když CoinEx pustí validátory, nebo hard forkem (vyměnit validátory) — žádnou snahu jsem nenašel. CET ERC-20 na Ethereu (`0x081F67aFA0cCF8c7B17540767BBe95DF2bA8D97F`, 201,1 mil.) běží dál bez CoinExu i bez forku (kontrakt neumí mint ani freeze), ale ~89 % leží v peněžence „CoinEx 48“ a likvidita na DEX ~790 USD. Výběry do 22. 12. 2026 02:00 UTC, pak úschova s poplatkem 5 %/měsíc, nárok přes support@coinex.com do 22. 8. 2028. Pozor na podvodná „nová oznámení“ jménem CoinExu.

## 17. 10. 2026 večer: DynaCache, velká jádra, úklid Zinku (uživatel: „3 nic speciálního pro openttd.exe, budu hrát Morrowind. 2 ano, 1 ano“)

- **Nic speciálního pro openttd.exe** (žádná sekce v box64rc) — všechno obecně, uživatel bude hrát i Morrowind.
- **DynaCache zapnutá** (`GuestProgramLauncherComponent`): `BOX64_DYNACACHE=1`, `BOX64_DYNACACHE_LIMIT=512` (MiB na disku, složka `$HOME/.cache/box64` = home kontejneru v data/data). Proměnné kontejneru/zástupce to pořád přebijí (`BOX64_DYNACACHE=0` vypne).
  - Upstream ji pro Winlator (WINLATOR_GLIBC) vypnul commitem 03157ce4 bez důvodu. Po 0.4.4 ale přišly 4 opravy cache: #4483 (načtené bloky `to_delete` se používaly → starý kód), #4482 (pád ve fatálním signálu zapsal rozbitou cache → padalo by to i příště), #4484 (rozbitý soubor se smaže), #4485 (exit_group). Proto **vlastní build Box64 = v0.4.4 + tyto 4 patche**: `tools/box64/build.sh`, patche v `tools/box64/patches/`. Stejné volby jako upstream ANDROID_GLIBC. Interpreter/runpath musí být při linkování (patchelf → segfault).
- **Velká jádra** (`CPUStatus.getBigCoreList()`): z `/proc/cpuinfo` vyřadí LITTLE jádra (A35/A53/A55/A510/A520, Kryo Silver). Použije se jen když zbydou aspoň 4 velká (Redmi A5 má 2× A75 → nechá všechna). Je to nový **výchozí seznam CPU** (`Container.getFallbackCPUList`) → nové kontejnery. **Starý kontejner má uložený seznam „všechna jádra“ → v nastavení odškrtnout LITTLE jádra** (Motorola Dimensity 7400: 0–3 = A55, 4–7 = A78).
  - **Od startu:** `ProcessHelper.exec(..., affinityMask, ...)` nastaví affinity vláknu, které spouští box64 (`ThreadAffinity.setMask`, JNI `thread_affinity.c`), a hned ji vrátí. Dítě ji zdědí → wine, wineserver i hra běží na vybraných jádrech od začátku, ne až od prvního okna. WinHandler při prvním okně nastaví stejnou masku (beze změny).
  - Test `app/src/test/java/com/winlator/BigCoresTest.java` (6 testů: Dimensity 7400, SD 8 Gen 3, Kryo, Redmi A5, bez LITTLE, x86).
- **Zink:** moje změna z buildu 5 (`real`) vrácena na Brunův původní řádek — nic nedělala (viz výše).
- **Uživatel 17. 10.: „uděláme si svoje OpenGL, jestli nám tohle nestačí. Podívej se, co by se dalo v OpenGL zlepšit, budu používat svoje OpenGL pořádný.“** → běží 3 rozbory: Gladio (host zdroj v repu, guest libGL jen binárka), nový Zink přes Vortek, VirGL (host virglrenderer v repu, guest Mesa 23.1.9). Kontejner má ve výchozích env už `mesa_glthread=true`, `MESA_SHADER_CACHE_MAX_SIZE=512MB`, `ZINK_DESCRIPTORS=lazy`.

## 17. 10. 2026: Rozbory OpenGL (3 agenti, jen čtení + stavby ve scratchpadu)

**VirGL** (host = `app/src/main/cpp/virglrenderer`, základ ~virglrenderer 0.8.1 z 2019, vtest nahrazen JNI „virgl_server“; guest = Mesa 23.1.9 gallium xlib, zdroj `brunodev85/mesa3d-custom`):
- Na Mali (GLES 3.2) host hlásí GLSL 310 nafouknuté na 430; bez override by guest hlásil jen GL 2.1 → override je nutný. Host verze < 6 → **legacy kontext má GLSL max 1.40** → výchozí 3.1 je pro legacy konzistentní, proto výchozí zůstává 3.1 (3.3 by legacy aplikacím lhalo o shaderech). Core kontexty (OpenTTD, WineD3D) dostanou až override.
- **4.4+ nefunguje ze 3 důvodů najednou:** host nemá `VIRGL_CAP_ARB_BUFFER_STORAGE`, host verze 3 (<4), guest winsys nemá `supports_coherent`. → **seznam verzí omezen na 4.3** (staré uložené 4.4–4.6 se čtou jako 4.3). Cesta k 4.4: emulovaná koherentní paměť (guest + host, střední práce) nebo AHardwareBuffer (velká).
- Výkon: každý snímek host točí `glClientWaitSync(…,0)` + `eglMakeCurrent` ve smyčce (spin, CPU a GPU úplně serializované), 2 přepnutí kontextu na každý požadavek, present = `glCopyTexImage2D` s novou alokací textury každý snímek. **`mesa_glthread=true` v xlib frontendu nic nedělá** (jen DRI). Žádná env proměnná nezrychlí. Opravy na hostu: blokující čekání, překryv snímků, `glCopyTexSubImage2D` do předalokované textury, busy stav po zdrojích, host shader cache.
- Mesa xlib GLX frontend existuje do **26.2.x**, smazán až v 26.3 (commit 7055daf6, 8. 10. 2026).

**Gladio** (host `app/src/main/cpp/gladiorenderer`, **guest zdroj veřejný: `brunodev85/gladio` (LGPL-2.1), submodul v brunodev85/winlator @116c0d1** — agent ho cross-buildil, exporty sedí s gladio-1.1):
- Přenos: 2 sdílené ringy (64 MB/32 MB, ashmem přes X11 socket), obě strany busy-wait (yield ×500, pak usleep 100 µs). Každý buffer 2× (ashmem stín + GLES). 57 míst s round tripem (glGen*, glGet*, fence, swap…). Swap čeká na host `glCopyTexImage2D`+`glFlush` → žádný překryv snímků.
- Hlásí GL 3.3/GLSL 3.30 natvrdo, 8 texturovacích jednotek, max textura 4096.
- **Poznámka „`sizeofGLType` nezná 8_8_8_8_REV“ je mylná** — týká se jen vertexů; textury počítá `computeTexImageDataSize`, který ho zná.
- **WineD3D je na Gladiu rozbité:** Gladio hlásí `GL_ARB_buffer_storage`, Wine 10.10 pak drží buffery persistentně namapované, Gladio host je nikdy nepřenese → vertexy/indexy/konstanty se nedostanou do GPU. Oprava H1: přestat hlásit buffer_storage (+ compute, derivative_control, abgr; a opravit počet rozšíření o 1).
- Další chyby: H2 pád v `glReadPixels` s PBO, H3 `glVertexAttrib4Nub*` no-op na hostu (D3DCOLOR), H6 zápis mimo pole pro COPY_READ/WRITE apod. (index 255), G1 pád guestu v `flushMappedPersistentBuffers`, **G2 velikost uploadu ignoruje `GL_UNPACK_ALIGNMENT` → OpenTTD R8 sprity mají spodní řádky smetí** (guest), převodník shaderů: chybí texture funkce ve vertex shaderu, shadow2D, *Lod, Rect, FragData[n>0]… (H7 prototyp).
- BGRA vertexy (každý D3DCOLOR) = hlavní cena na snímek pro D3D8/9 (kopie přes ring + prohazování R/B na CPU).
- Patche a harness: scratchpad `gladio-research/patches/` (host H1–H6, převodník H7, guest G1–G3), `harness/validate.sh`.

**Zink přes Vortek:**
- Vortek: Vulkan 1.3.128, pro „mesa zink“ jen pevný seznam 55 rozšíření; fakuje funkce (tesselace, dualSrcBlend, multiViewport…) a neumí je pak vypne; povrch jen `VK_KHR_xlib_surface` (žádný xcb → moderní kopper nejde); host-visible paměť sdílená přes fd → **persistentní koherentní mapování v principu funguje**.
- **Agent sestavil Mesa 26.2.5 Zink s xlib GLX** (jako Bruno u 22.2.5: kopie snímku přes CPU + XShmPutImage), patch 61 řádků: na x86 s lavapipe **GL 4.6 core i compat**, glxgears správně. aarch64 balík `zink-26.2.5-xlib-UNTESTED.tzst` (4,2 MB), GLIBC ≤ 2.38, všechny NEEDED knihovny v rootfs. Cross-build: sysroot z 65 .deb, configure 19 s, build 5 min. **Na telefonu neběželo.** Na Mali čekat nativně 3.3–4.0 (GL 4.1 chce maxViewports ≥ 16); `ARB_buffer_storage` je v Zinku vždy.
- Ostatní forky: GameNative (glibc) = Brunův zink-22.2.5 + override 3.3, virgl 3.1; Ludashi = Mesa 24.3.0 bionic + vlastní Vulkan wrapper; Cmod/Bionic = zink + wrapper ICD; Mobox na Mali VirGL. Nikdo nehlásí GL verzi na Mali.
- Vedlejší chyba z 11.2: `VortekRendererComponent.java:53` dělí seznam rozšíření podle `|`, ale `VortekConfigDialog.java:71` spojuje `:` → vlastní výběr rozšíření nic nenajde.
