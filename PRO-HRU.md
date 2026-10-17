# Pro kolegu, co dělá hru

Tohle je vzkaz z druhé strany, od Winlatoru DL. To je náš Winlator pro Android, repo
`grrrrshadow/cota`, ve kterém hráč hru hraje na Motorole Edge 60 Fusion. Tady je, co
se u nás děje a co se týká hry. Moje poznámky jsou v `temata.md` ve stejném repu.

---

## 1. Myš: pravé tlačítko zůstává držené (17. 10. 2026)

Podrobně v **`MYS-REPORT.md`** (ve stejném repu). Krátce:

- V obou logech z ladění myši po `WM_RBUTTONDOWN` nikdy nepřijde `WM_RBUTTONUP`.
- **Systém si celou dobu myslí, že je tlačítko dole.** `GetAsyncKeyState(VK_RBUTTON)`
  (`video/win32_v.cpp:1116`) vrací `R=true` při každém dotazu a `MK_RBUTTON` je i ve
  `wParam` každé `WM_MOUSEMOVE` (`sys=1`).
- Hra tedy nemá jak poznat, že je tlačítko nahoře. Puštění se ztratilo dřív, než se
  dostalo do Wine, tedy u nás: dotyk/čudlík → X server Winlatoru → winex11.
- Hra stav tlačítek sama změnit nemůže: `SendInput`, `mouse_event`, `keybd_event` ani
  `ClipCursor` v `src/` nejsou.
- **Hledat opravu v `map_drag.cpp` nemá smysl.** Ladění myši prosím nechte, bez něj by
  tohle nešlo poznat.
- Hráč říká, že na PC se to dělo taky už před deseti lety. Tam by ale `GetAsyncKeyState`
  hlásil skutečný stav, takže tamta chyba byla jiná.

Rozbor na naší straně běží. Výsledek doplním do `MYS-REPORT.md` a sem.

---

## 2. Hardwarová akcelerace (OpenGL) ve Winlatoru DL

- **Opraveno:** Winlator 11.2 ztrácel nastavení VirGL (verze OpenGL, Core profile). Proto
  hra dřív hlásila „nebyla nalezena kompatibilní grafická karta“: VirGL běžel na 3.1 a
  `opengl.cpp:580` chce aspoň 3.2. Teď se nastavení drží a akcelerace na VirGL jede.
- **VirGL: nejvýš 4.3.** Od 4.4 hra zapne persistent mapping (`opengl.cpp:607`), a ten
  VirGL na tomhle telefonu neumí, takže by hra hlásila „Can't get pointer to screen
  buffer“. V nastavení už verze nad 4.3 nejsou. Core profile hra nepotřebuje, o core
  kontext si řekne sama.
- **Rozpracováno:** Zink z Mesa 26.2.5 (OpenGL 4.x přes Vulkan). Ten persistent mapping
  umí, takže tam by hra mohla jet i na 4.4+. Na telefonu zatím nevyzkoušeno.
- **Gladio** (vlastní OpenGL Winlatoru) má chybu, která se hry týká přímo: velikost
  uploadu textury nebere ohled na `GL_UNPACK_ALIGNMENT`. Sprity R8 od hry
  (`opengl.cpp:1518-1529`, řádek zarovnaný na 4) pak mají spodní řádky smetí. Opravujeme
  to na straně Gladia. Shadery hry převodník Gladia přeloží správně, ověřeno na všech
  shaderech z 1.10 a 1.50.

## 3. Box64 (emulace procesoru)

- Zapnutá **DynaCache**: přeložený kód se ukládá na disk, takže od druhého spuštění by se
  mělo méně trhat, když hra poprvé narazí na nový kód. Do Box64 0.4.4 jsme přidali 4 opravy
  cache z novějšího vývoje.
- Cache se uloží, jen když se hra ukončí sama (Quit ve hře). Tlačítko Exit ve Winlatoru
  procesy zabije a cache se neuloží.
- Hra běží jen na **velkých jádrech** (A78) a od samého startu, ne až od prvního okna.
- Sekci pro `openttd.exe` v nastavení Box64 jsme schválně nedělali, nastavení je obecné.
- Trhání simulace (Game loop) tím nezmizí, to je výkon procesoru přes Box64. Kreslení se
  měří zvlášť: v okně Frame rate ve hře je vidět, co zrovna brzdí.
