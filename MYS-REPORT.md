# Report: pravé tlačítko myši zůstává držené

Hráč 17. 10. 2026: *„pravý čudlík myši je zaseklý zmáčklý, dokud ho znova nezmáčknu“*,
*„to dělalo i na PC před deseti lety, to je chyba hry a nikdo neví“*.

Podklady: dva logy z ladění myši ve hře (`mouse_debug.cpp`, `map_drag.cpp` v repu hry),
uložené 17. 10. v 01:45:49 a 13:29:20. Hra běží ve Winlatoru DL (Wine 10.10 + Box64) na
Motorole Edge 60 Fusion.

**Stav (18. 10.): příčina nalezena, je ve Winlatoru (X server), oprava je v commitu
`3ba19e2`.** Test ji předvede: s původním kódem 2 z 5 případů selže, s opravou projde všech
5. Na telefonu ještě nevyzkoušeno, nezávislá kontrola opravy běží. Příčina je v kapitole 4.

---

## 1. Co jistě plyne z logů

V obou logech hra po `WM_RBUTTONDOWN` už nikdy nedostane `WM_RBUTTONUP`. Skončí to až
dalším `WM_RBUTTONDOWN`, když hráč tlačítko zmáčkne znovu. Hra to sama pozná a zapíše
`!!! ROZPOR … zprava o pusteni se ztratila`.

**Systém si celou dobu myslí, že je tlačítko dole.** Hra se ptá dvěma způsoby a oba
odpovídají stejně:

- `GetAsyncKeyState(VK_RBUTTON)` (`video/win32_v.cpp:1116`, v logu `dotaz na system`)
  hlásí `R=true` při každém dotazu, asi čtyřikrát za sekundu. V logu 1 je to 33 dotazů
  z 33, v logu 2 všechny.
- `MK_RBUTTON` ve `wParam` každé `WM_MOUSEMOVE` (`win32_v.cpp:692`, v logu `sys=1`) je
  také pořád nastavený.

Na skutečných Windows `GetAsyncKeyState` vrací fyzický stav tlačítka. Ve Wine vrací to,
co Wine dostalo jako vstup od X serveru, tedy tady od X serveru ve Winlatoru. Zpráva
o puštění se tedy ztratila **dřív, než se dostala do Wine**. Hra ji ztratit nemohla:
`SendInput`, `mouse_event`, `keybd_event` ani `ClipCursor` v ní nejsou (grep přes `src/`).
Hra má navíc `SetCapture` při stisku a `ReleaseCapture` až po puštění všech tlačítek
(`win32_v.cpp:591, 615, 620, 640`), takže ani puštění mimo okno se jí ztratit nemá.

Hráč říká, že na PC se to dělo taky. Na PC by ale `GetAsyncKeyState` hlásil puštěné
tlačítko, takže tamta chyba byla jiná (spíš tehdy ve hře, třeba bez `SetCapture`).
Tady jde o ztrátu cestou: telefon → Winlator (dotyk, čudlík na obrazovce, myš) →
X server Winlatoru → winex11 ve Wine.

## 2. Log 1 (01:45:49), časy v ms od startu hry

| čas | co se stalo |
|---|---|
| 2542207 | předchozí tažení skončilo normálně: `WM_RBUTTONUP`, `GetAsyncKeyState` R=false, „drženo 28874 ms“ |
| 2548324 | `WM_RBUTTONDOWN` v (488,353), začíná tažení mapy (`nase=1`), ukazatel přišpendlený (`pin=1`) |
| 2548342–2555926 | 375× `WM_MOUSEMOVE` o 1 px vedle → hra vrátí ukazatel `SetCursorPos` → další `WM_MOUSEMOVE` zpátky na (488,353). Opakuje se to po ~17 ms, pohyb je jen o 1 px. |
| 2548326–2556648 | každý dotaz `GetAsyncKeyState` vrací R=true |
| 2556907 | `WM_CAPTURECHANGED`, nový `WM_RBUTTONDOWN`, hra hlásí ztracené puštění (8582 ms po začátku) |

## 3. Log 2 (13:29:20)

- Pravé tlačítko je dole už od začátku 20sekundového výřezu. Tažení začalo 29015 ms
  před koncem logu.
- Mezitím přichází opakovaně `WM_CAPTURECHANGED` a hned `WM_MBUTTONDOWN`/`WM_MBUTTONUP`
  (prostřední tlačítko, stisk trvá 80–120 ms). Při každém stisku prostředního hra volá
  `SetCapture`. `ReleaseCapture` po puštění neudělá, protože si myslí, že pravé je pořád
  dole.
- `GetAsyncKeyState` po celou dobu vrací R=true, `sys=1`.
- 1509554: nový `WM_RBUTTONDOWN` → hra hlásí ztracené puštění.

## 4. Příčina

Spouští to přišpendlený ukazatel při tažení mapy, ale chyba je ve Winlatoru:

1. Hra při tažení pravým tlačítkem volá skoro na každou `WM_MOUSEMOVE` `SetCursorPos`
   (`map_drag.cpp:172`, `video/win32_v.cpp:703-720`).
2. Wine (winex11, `X11DRV_SetCursorPos`, wine-10.10 `dlls/winex11.drv/mouse.c:1394-1410`)
   pro každý takový posun udělá `XGrabPointer` na své kořenové okno, počká na odpověď,
   pak `XWarpPointer` a `XUngrabPointer`. Hra běží pod `explorer /desktop=`, takže tím
   kořenovým oknem je okno virtuální plochy, které patří procesu explorer.
3. Podle X11 drží stisknuté tlačítko takzvaný implicitní grab: puštění musí dostat
   stejné okno jako stisk. **X server Winlatoru ale nechal grab od Wine ten implicitní
   nahradit** (`GrabRequests.java:41-50`, `GrabManager.java:54-66`) a `UngrabPointer`
   pak smazal grab úplně (`GrabRequests.java:61-64`).
4. Když hráč pustí prst zrovna mezi `XGrabPointer` a `XUngrabPointer`, puštění dostane
   okno plochy. Wine v procesu hry k oknu plochy nemá data (patří exploreru), takže
   puštění zahodí (`event.c:453-455`, `mouse.c:550`). Do wineserveru se nedostane, proto
   `GetAsyncKeyState` i `MK_RBUTTON` dál hlásí „dole“.
5. Okénko je krátké: jedna cesta Wine → Box64 → X server a zpět. Hra ho ale otevírá asi
   50× za sekundu, takže při dlouhém tažení se do něj puštění dřív nebo později trefí.
6. Winlator sám má tlačítko už puštěné, takže další stisk je nový `ButtonPress` →
   `WM_RBUTTONDOWN` a hlášení `ROZPOR`. Přesně tak oba logy končí.

Vysvětluje to i **zdvojené řádky** `-> ukazatel vracen` v logu 1 (202 z 683): po prvním
posunu už tlačítko nemá žádný grab, takže každý další `XGrabPointer` začne „od nuly“ a
explorer dostane `EnterNotify`. Na to explorer vrátí kurzor zpátky, a hra tak dostane
pohyb dvakrát. Tažení je proto trhanější.

Ostatní podezření vyloučila data: ztráty na straně Androidu (zrušený dotyk, přepínač,
změna profilu, externí myš) by nechaly tlačítko držené i ve Winlatoru, a pak by další
stisk žádné `WM_RBUTTONDOWN` neposlal. Oba logy ale novým `WM_RBUTTONDOWN` končí.

## 5. Oprava (Winlator DL, commit `3ba19e2`)

Když už je tlačítko držené a stejný klient chce grab na okno, které je předkem okna
s tlačítkovým grabem (tedy grab od `SetCursorPos`), X server ho potvrdí, ale tlačítkový
grab nechá. Následný `UngrabPointer` pak nic nesmaže. Puštění tak vždy dostane okno hry.
Odpadnou i zdvojené pohyby. Grab od Wine pro `ClipCursor` a posuny bez drženého tlačítka
se chovají jako dřív.

Test `app/src/test/java/com/winlator/StuckRightButtonGrabTest.java` pouští skutečný kód
X serveru (`GrabRequests.grabPointer`, `WindowRequests.warpPointer`, `ungrabPointer`)
s okny, jaká vytváří Wine. Bez opravy: 2 z 5 selžou (puštění dostane okno 0x200001 =
plocha místo 0x400001 = hra; po prvním posunu tlačítko nemá grab). S opravou: 5 z 5.

## 6. Pro hru

- Ladění myši je výborné, bez něj by se příčina nenašla. Prosím nechat ho tam.
- Chyba je ve Winlatoru a je opravená tam. Hra nic měnit nemusí.
- Kdyby se to mělo zmírnit i ze strany hry (pro jiné Winlatory a Wine bez naší opravy):
  volat `SetCursorPos` méně často, třeba až když ukazatel uteče o několik px, ne při
  každém 1px pohybu. Každé volání otevírá to okénko.
- Hráč říká, že na PC se to dělo taky. Na skutečných Windows `SetCursorPos` žádný grab
  nedělá, takže tahle chyba tam nastat nemohla. Pokud se na PC tlačítko zasekávalo, byla
  to jiná chyba.
