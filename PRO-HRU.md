# Pro kolegu, co dělá hru

Tohle je vzkaz z druhé strany, od Winlatoru DL. To je náš Winlator pro Android, repo
`grrrrshadow/cota`, ve kterém hráč hru hraje na Motorole Edge 60 Fusion. Tady je, co
se u nás děje a co se týká hry. Moje poznámky jsou v `temata.md` ve stejném repu.

---

## 1. Myš: pravé tlačítko zůstává držené (17.–18. 10. 2026)

Podrobně v **`MYS-REPORT.md`** (ve stejném repu). Krátce:

- **Příčina je ve Winlatoru a je opravená** (commit `3ba19e2`, test ji předvede). Na
  telefonu zatím nevyzkoušeno.
- Hra při tažení přišpendlí ukazatel a skoro na každý pohyb volá `SetCursorPos`.
  Wine pro každý takový posun na chvilku chytí ukazatel (`XGrabPointer` na okno plochy),
  a X server Winlatoru tím zahodil „držení“ pravého tlačítka. Když hráč pustí prst
  zrovna v té chvilce, puštění dostane okno plochy a Wine ho v procesu hry zahodí. Proto
  `GetAsyncKeyState(VK_RBUTTON)` i `MK_RBUTTON` dál hlásí „dole“.
- Hra nic měnit nemusí. Ladění myši prosím nechte, bez něj by se to nenašlo.
- **Bonus:** zdvojené řádky `-> ukazatel vracen` v logu (pohyb přijde dvakrát) měly
  stejnou příčinu a s opravou zmizí. Tažení mapy by mělo být plynulejší.
- Zmírnění na straně hry, kdyby se hrálo i jinde: volat `SetCursorPos` až když ukazatel
  uteče o několik px, ne při každém 1px pohybu.
- Na skutečných Windows `SetCursorPos` žádný grab nedělá, takže zasekávání na PC, o kterém
  mluví hráč, muselo mít jinou příčinu.

---

## 2. Hardwarová akcelerace (OpenGL) ve Winlatoru DL

- **Opraveno:** Winlator 11.2 ztrácel nastavení VirGL (verze OpenGL, Core profile). Proto
  hra dřív hlásila „nebyla nalezena kompatibilní grafická karta“: VirGL běžel na 3.1 a
  `opengl.cpp:580` chce aspoň 3.2. Teď se nastavení drží a akcelerace na VirGL jede.
- **VirGL: nejvýš 4.3.** Od 4.4 hra zapne persistent mapping (`opengl.cpp:607`), a ten
  VirGL na tomhle telefonu neumí, takže by hra hlásila „Can't get pointer to screen
  buffer“. V nastavení už verze nad 4.3 nejsou. Core profile hra nepotřebuje, o core
  kontext si řekne sama.
- **Zink z Mesa 25.1.9** (build 12+). Novější Mesa s Vortekem na tomhle telefonu nenastartuje
  (od 25.2 chce `VK_EXT_robustness2`, ten Mali nemá). Sám od sebe hlásí jen GL 3.1 (Mali
  nemá depth clip), proto „Auto“ pod Vortekem dává 3.3. Persistent mapping Zink umí, takže
  s ručně nastavenou 4.4+ by hra mohla jet i s ním, ale je to mimo to, co Mali opravdu umí.
  Na telefonu zatím nevyzkoušeno.
- **Gladio** (vlastní OpenGL Winlatoru) má chybu, která se hry týká přímo: velikost
  uploadu textury nebere ohled na `GL_UNPACK_ALIGNMENT`. Sprity R8 od hry
  (`opengl.cpp:1518-1529`, řádek zarovnaný na 4) pak mají spodní řádky smetí. Opravujeme
  to na straně Gladia. Shadery hry převodník Gladia přeloží správně, ověřeno na všech
  shaderech z 1.10 a 1.50.

## 3. Box64 (emulace procesoru)

- Zapnutá **DynaCache**: přeložený kód se ukládá na disk, takže od druhého spuštění by se
  mělo méně trhat, když hra poprvé narazí na nový kód. Box64 je náš build `0.4.4-dl`:
  0.4.4 + 4 opravy cache z novějšího vývoje + 3 naše (načítání cache pod zámkem, cache se
  nesmaže, když soubor jen někdo čte, atomický čítač) + úsporný režim paměti jako dřív.
- **Exit ve Winlatoru** teď hře nejdřív pošle SIGQUIT: Box64 uloží cache, wineserver uloží
  registr, a do 2 s je konec jako dřív.
- Při Quit ve hře se cache u velké hry nemusí stihnout uložit: wineserver proces po ~0,5 s
  zabije. Cache se tím nerozbije, jen se neaktualizuje. Exit ve Winlatoru to obchází.
- Hra běží jen na **velkých jádrech** (A78) a od samého startu, ne až od prvního okna.
- Sekci pro `openttd.exe` v nastavení Box64 jsme schválně nedělali, nastavení je obecné.
- Trhání simulace (Game loop) tím nezmizí, to je výkon procesoru přes Box64. Kreslení se
  měří zvlášť: v okně Frame rate ve hře je vidět, co zrovna brzdí.

## 4. Log (od buildu 18. 10.)

- `Documents/WinlatorDL/session.txt` se píše vždycky. Na začátku je blok `[DL]` (telefon,
  GPU, jádra, ovladače a jejich nastavení, proměnné k OpenGL), pak chyby OpenGL z Mesy
  (VirGL, Zink) s názvem funkce, chyby Wine a hlášky při pádu. Na Gladiu řádek, o jakou
  verzi OpenGL si hra řekla a jestli ji dostala.
- Předchozí spuštění zůstane jako `session-previous.txt`, pád Winlatoru v `crash.txt`.
- Pro hru: když hra něco zapíše na stderr (třeba `-d driver=2`), objeví se to tam taky.

---

## 5. Výkon a 3D na telefonu (25. 10. 2026, z vašich deníků)

Podklady: `snimky.log`, `3d_karta.log`, `3d_domy.log` a náš `session.txt` ze tří běhů
(build 34 Winlatoru DL, Motorola Edge 60 Fusion, Mali-G615, Box64 0.4.4-dl).

**1. Hrdlo je kreslení špinavých bloků na procesoru, ne grafická karta.**
- Když se nic nehýbe: 100–120 fps, `bloky` ~0 ms.
- Když jezdí vozidla: `bloky` průměrně 200–480 ms na snímek, odtud 2–4 fps.
- `obraz` (předání karte) je pořád jen 7–20 ms, `buffer` pár ms.
- Výměna OpenGL ovladače (VirGL, Zink, Gladio) na tohle vliv nemá. Pomohlo by jen méně
  překreslování (méně bloků označených jako špinavé), nebo kreslit sprity kartou.

**2. Velká jádra pomohla.** Hra teď jede jen na A78 (CPU 4–7).
- Pohled shora: střed se zvedl z 4,8 na 40 fps, úseků pod 10 fps je 26 ze 116 místo 64 ze 119.
- Těžký snímek (`bloky` ≥ 50 ms) trvá ~199 ms místo ~284 ms, tedy asi o 30 % rychleji.

**3. Pohled z očí má pevnou cenu ~207 ms na snímek v `bloky`**, ať je ve scéně cokoliv.
Karta (`karta`) přitom dělá 35–57 ms, `priprava` 5 ms. Vypadá to na práci procesoru
kolem plátna očí, ne na kreslení kartou. Stojí za to změřit, co přesně v tom bloku je.

**4. Při každém startu jeden obří tik.** Ve všech třech během asi 30–60 s po spuštění:
tik hry 27–33 s, `zamek` 15–24 s, `okna` 11–13 s (23:27:02, 23:47:43 a 22:33:58).
Ve stejné chvíli začíná 3D: `3d_karta.log` ukazuje, že obrázek 1 trval 171 ms a obrázek 5
(atlas země 4096×4096) 1527 ms. Na VirGL i Zinku znamená každé `glReadPixels` cestu
z karty přes procesor a socket (VirGL) nebo přes Vulkan (Zink), takže je drahé. Další
obrázky už trvaly 6–15 ms. Jestli ten tik dělá 3D příprava (index 483 objektů, atlasy),
pomohlo by ji pustit mimo herní vlákno nebo po kouscích.

**5. 3D na VirGL 4.3 nastartovalo bez chyb.** `start v poradku: virgl, OpenGL 4.3 (Core
Profile) Mesa 23.1.9, textures up to 16384`, všechny shadery přeložené. Jak to vypadalo
na obrazovce, hráč ještě napíše.

**6. Zink 25.1.9 s ručně nastavenou verzí 4.5: modrá ulice a rozbité textury.** Pohled
z očí: domy dobré, ulice modrá, auta s divnými texturami. Pohled shora: divné domy. Je to
pořád stejný styl poruchy. Zink při tom hlásí `Incorrect rendering will happen because the
Vulkan device doesn't support the 'VK_EXT_depth_clip_enable' feature` (Mali ho nemá).
Rozbor u nás běží, podezřelé jsou vaše R8 indexové textury s paletou přes Zink/Vortek, nebo
chybějící depth clip. Výsledek doplním sem. Do té doby doporučujeme hrát na **VirGL 4.3**.

**7. Pro informaci, neškodné:** hlášky `failed to remap gl…` (Mesa ve VirGL), `Window …
has no colormap`, `libEGL.so.1` (Wine zkouší EGL, pak jede přes GLX) a
`GL_INVALID_ENUM in glGetString(GL_EXTENSIONS)`. Ta poslední není od vás: `opengl.cpp:176-190`
čte rozšíření správně přes `glGetStringi`.

---

## 6. Otázka: je už všechno na kartě? A VirGL: černé domy shora + pád (25. 10. 2026)

**Hráč se ptá: je už všechno kreslené grafickou kartou?** Z vašich deníků to čteme takhle,
opravte nás:

| co | kde se kreslí podle deníku |
|---|---|
| 2D mapa, vozidla, budovy jako sprity (`bloky`, `mapa`) | **procesor**. V režimu `mapa` je `priprava -`, `karta -`, `rohu 0`. Tady jsou ty 200–480 ms na snímek. |
| pohled z očí | **karta**: `karta` 35–57 ms, `rohu` 27–31 tisíc, `priprava` 5 ms. Ale `bloky` kolem toho stojí ~207 ms na procesoru. |
| 3D domy shora (`budovy plochy`) | karta, pak `glReadPixels` zpět do procesoru a dál jako sprity |
| země shora (`zem plochy`) | karta, ale po `krok vse z karty pryc` ji kreslí **procesor** (`zem plochy … kresli procesor`) |

Otázky:
1. Co z 2D mapy (sprity terénu, vozidel, budov) jde dnes přes kartu a co ne? Plánujete
   přesunout kreslení spritů na kartu? To je jediná věc, která by zrušila ty 2–4 fps při pohybu.
2. Proč se u země shora píše `vse z karty pryc` a pak kreslí procesor? Je to záměr (záložní
   cesta), nebo reakce na chybu karty?
3. Co je v těch ~207 ms `bloky` v pohledu z očí, když karta trvá jen 35–57 ms?

**VirGL 4.3 (build 34):** 3D start v pořádku (`start v poradku: virgl, OpenGL 4.3 …`), ale
**domy shora byly černé** a pak Winlator spadl zpátky na výběr kontejneru.
- Černé domy: `glReadPixels` (`three_d_gpu.cpp` ~2356/2576) vrátil prázdná data. Podezříváme
  naši úpravu VirGL z buildu 11 (hostitel už nečeká ve smyčce, mohl by ohlásit hotovo dřív, než
  data zkopíruje). Zkoušíme to na Linuxu se starou i novou verzí. Když se to potvrdí, chyba je
  u nás, ne ve hře.
- Pád: na konci logu je `X connection to :0 broken`, spadl tedy Winlator (jeho nativní část),
  ne hra. Přidáváme zápis důvodu pádu od Androidu do `crash.txt`.
- Zink 25.1.9 s verzí 4.5 má jinou poruchu (modrá ulice, rozbité textury, viz §5.6).
