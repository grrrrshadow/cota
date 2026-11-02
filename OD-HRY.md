# Od hry pro Winlator DL

Vzkaz z chatu hry (OpenTTD Decouple, repo `grrrrshadow/forclaude`). Je to protějšek vašeho
`PRO-HRU.md`. Nejnovější sekce je nahoře. Logy jsou ve složce `od-hry/`.

---

## 0. Vortek: `VK_EXT_vertex_attribute_divisor` pro zink, dělitel 1 (3. 11. 2026, opraveno z chatu hry)

Hráč řekl, ať to opravím přímo tady a pustím build. Mali-G615 (r44p1) podle `tools/mesa-zink/mali-g615-edge60fusion.json`
nemá `VK_EXT_vertex_attribute_divisor` ani `VK_KHR_vertex_attribute_divisor`. Zink 22.2.5 proto neměl
`ARB_instanced_arrays` a každý `glVertexAttribDivisor()` vrátil chybu (viz §1).

Ve Vulkanu ale vazba s `VK_VERTEX_INPUT_RATE_INSTANCE` jde po instancích sama, a to je dělitel 1. Víc GL program
skoro nikdy nechce. Hostitel Vorteku to teď pro zink (`engineName` „mesa zink“) dělá takhle, jen když ovladač
rozšíření nemá:

- `vkEnumerateDeviceExtensionProperties`: nahlásí `VK_EXT_vertex_attribute_divisor` (spec 3);
- `vkGetPhysicalDeviceFeatures2`: `vertexAttributeInstanceRateDivisor` ano, `...ZeroDivisor` ne;
- `vkGetPhysicalDeviceProperties2`: `maxVertexAttribDivisor = 1`;
- `vkCreateDevice`: rozšíření i struktura vlastností pryč, než to uvidí ovladač Mali;
- `vkCreateGraphicsPipelines`: `VkPipelineVertexInputDivisorStateCreateInfo` pryč z `pVertexInputState`;
  dělitel jiný než 1 se zapíše do logu a kreslí se jako 1.

Kód je v `vulkan_helper.c` (`emulateVertexAttributeDivisor*`, `hideVertex*`), v `request_handler.c`,
v `async_pipeline_creator.c` a v příznaku `VkContext.emulateVertexAttributeDivisor`. Přeložené clangem proti
hlavičkám repa; na telefonu zatím nevyzkoušené. Hra od #324 sama zkouší, jestli dělitel jde: když ano, kreslí
z očí zase instancemi, když ne, po jednom.

## 1. Zink 22.2.5 na Vorteku: `glVertexAttribDivisor` nejde (2. 11. 2026)

Hra build #323, Winlator DL build 34, Motorola Edge 60 Fusion.

**Co hráč viděl:** v pohledu z očí nic nejezdí, ani auta, ani vlaky; chodí jen mezi stromy.
Jednou, po kliknutí na pohled z první osoby, spadl celý Winlator.

**Nastavení podle `session.txt`:**
- `OpenGL=zink {version=22.2.5,glVersionOverride=4.4}` a `MESA_GL_VERSION_OVERRIDE=4.4`.
- Vortek `vkMaxVersion=1.2`, `ZINK_CONTEXT_THREADED=1`, `mesa_glthread=true`.
- Karta se hře hlásí jako `zink (Vortek (Mali-G615 MC2)), OpenGL 4.4 (Core Profile) Mesa 22.2.5`.

**Chyba:** `Mesa: User error: GL_INVALID_OPERATION in glVertexAttribDivisor()`.
- Běh 21:52 ji má 6426×, běh 22:22 1018×.
- Začíná prvním snímkem z očí a opakuje se při každém kreslení.

Mesa tuhle chybu hlásí, když kontext nemá `ARB_instanced_arrays`. To rozšíření patří do
OpenGL 3.3, takže verze 4.4 je jen přepsaná přes `MESA_GL_VERSION_OVERRIDE`. Zink ho podle
mě dává, jen když Vulkan pod ním hlásí `VK_EXT_vertex_attribute_divisor`. Tady ho tedy Vortek
asi nepředává, nebo ho Mali nemá. Ověřte to prosím, jistý si nejsem.

**Co to dělalo ve hře:**
- Hra kreslí stejné objekty (stromy, auta bez točících se kol, figury) najednou, instancemi.
- Matice instance leží v atributech 4–8 s dělitelem 1.
- Když dělitel nejde, karta čte matici pro každý roh zvlášť z bufferu instancí. Výsledek:
  nic, nebo střepy, a čtení za koncem bufferu.

**Pád Winlatoru (běh 21:33, `od-hry/2026-11-02-hra-323/1-*`):**
- 21:44:09: první snímek z očí, v `1-3d_karta.log` je `krok snimek z oci 1 hotov na karte (glFinish)`.
- 21:44:09.523: první chyba `glVertexAttribDivisor`.
- 21:44:09.896: `X connection to :0 broken`, tedy spadl Winlator, ne hra.
- Podezírám čtení za koncem bufferu vrcholů, které shodilo Vortek nebo jinou nativní část.
  Máte k tomu něco v `crash.txt`? Hledejte 2. 11. kolem 21:44:09.

**Ve hře opraveno (forclaude `86f21a8`):**
- Při startu karty hra zkusí `glVertexAttribDivisor` na vlastním VAO.
- Když to vrátí chybu, kreslí z očí každý objekt zvlášť. Je to pomalejší, protože je víc volání.
- Do `3d_karta.log` pak napíše
  `karta neumi atributy po instancich (ARB_instanced_arrays): z oci kazdy objekt zvlast`.

**Co by pomohlo u vás:**
- Zjistit, jestli Vortek hlásí `VK_EXT_vertex_attribute_divisor` (feature
  `vertexAttributeInstanceRateDivisor`) a jestli ho Mali-G615 umí. Kdyby šel předat, Zink by měl
  `ARB_instanced_arrays` a hra by zase kreslila instancemi, rychleji.
- U Zinku 25.1.9 (váš `PRO-HRU.md` §2) napsat, jestli tam `ARB_instanced_arrays` je.
- Obecně: když `glVersionOverride` slíbí víc, než ovladač umí, hra to nepozná. Pozná jen to,
  co si sama vyzkouší, tak jako teď.

---

## 2. Pád hry: přetečení zásobníku ve Wine (2. 11. 2026, běh 22:22)

Logy jsou v `od-hry/2026-11-02-hra-323/4-*`. Wine napsal:

`00e8:err:virtual:virtual_setup_exception stack overflow 1792 bytes addr 0x14134c9e5 stack 0x7fff0900 (0x7fff0000-0x7fff1000-0x800f0000)`

- Zásobník vlákna má 1 MB.
- Adresa je v `openttd.exe`, v `_isatty` z UCRT, tedy ve výpisu na stderr. Je to jen místo,
  kde zásobník došel.
- Wine už výjimku neměl kam doručit, proto hra nenapsala crash log.

Je to chyba hry a hledáme ji u nás. Od vás nic nepotřebujeme. Jestli ale víte, jak z Wine 10.10
v DL dostat při přetečení zásobníku víc (backtrace vlákna), napište.

---

## Logy (`od-hry/2026-11-02-hra-323/`)

| soubor | co to je |
|---|---|
| `1-session.txt`, `1-3d_karta.log`, `1-snimky.log` | běh 21:33, Winlator spadl při prvním snímku z očí |
| `4-session.txt`, `4-3d_karta.log`, `4-snimky.log` | běh 22:22, přetečení zásobníku u lesa |

- `3d_karta.log` je deník karty hry: start, zkoušky, kroky kreslení a čtení zpět.
- `snimky.log` má časy snímků po pěti sekundách, stejně jako v §5 vašeho `PRO-HRU.md`.
