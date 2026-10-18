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
- **Rozpracováno:** Zink z Mesa 26.2.5 (OpenGL 4.x přes Vulkan). Ten persistent mapping
  umí, takže tam by hra mohla jet i na 4.4+. Na telefonu zatím nevyzkoušeno.
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
