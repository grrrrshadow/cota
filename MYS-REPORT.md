# Report: pravé tlačítko myši zůstává držené

Hráč 17. 10. 2026: *„pravý čudlík myši je zaseklý zmáčklý, dokud ho znova nezmáčknu“*,
*„to dělalo i na PC před deseti lety, to je chyba hry a nikdo neví“*.

Podklady: dva logy z ladění myši ve hře (`mouse_debug.cpp`, `map_drag.cpp` v repu hry),
uložené 17. 10. v 01:45:49 a 13:29:20. Hra běží ve Winlatoru DL (Wine 10.10 + Box64) na
Motorole Edge 60 Fusion.

**Stav: příčina se pořád hledá.** Níž je, co z logů jistě plyne. Výsledek rozboru
doplním sem.

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

## 4. Co se zkoumá

Rozbor běží na straně Winlatoru DL (repo `grrrrshadow/cota`):

1. Čudlíky na obrazovce (`InputControlsView`, `ControlElement`): kde se stisk pošle a
   puštění ne (prst sjede z čudlíku, druhý prst, zrušený dotyk, přepínač).
2. Touchpad a skutečná myš (`TouchpadView`): pravý klik dvěma prsty, zpožděné puštění
   o 30 ms s kontrolou stavu, externí myš.
3. X server Winlatoru: podle X11 má `ButtonRelease` dostat stejné okno jako `ButtonPress`,
   i když se ukazatel mezitím přesunul (`XWarpPointer` po `SetCursorPos`) nebo se pod ním
   objevilo jiné okno. Zkoumá se, jestli to Winlator tak dělá.
4. Wine 10.10 winex11: kdy přijaté `ButtonRelease` zahodí, třeba když okno nepatří Wine.

Kandidáty pak ověří další agent, který je zkusí vyvrátit.

## 5. Pro hru

- Ladění myši je výborné. Bez něj by nešlo poznat, že se puštění ztrácí ještě před Wine.
  Prosím nechat ho tam.
- Hledat opravu v `map_drag.cpp` nemá smysl: hra dostává od systému „pořád dole“ ze
  všech stran.
- Pozorování: přišpendlený ukazatel posílá po každém `SetCursorPos` další `WM_MOUSEMOVE`,
  v logu 1 asi 50 vrácení za sekundu, i když se ukazatel hýbe jen o 1 px. Chybu to nevysvětluje, ale může to souviset
  s bodem 3 (posun ukazatele v X serveru).
