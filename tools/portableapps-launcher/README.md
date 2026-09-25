# PortableApps.com Launcher bez hlášky „did not close properly“

`TotalCommanderPortable.exe` je spouštěč PortableApps.com Launcher (GPL-2.0,
https://github.com/PortableApps/launcher, commit 5470210), ne Total Commander.
Když ho Winlator při Exit zabije, zůstane mu `Data\PortableApps.comLauncherRuntimeData-*.ini`
a příští start jen ukáže hlášku a skončí (`PortableApps.comLauncher.nsi`, řádek 428,
`MessageBox MB_ICONSTOP $(LauncherCrashCleanup)` — žádný klíč v registru se tam nečte).

`no-crash-message.patch` hlášku odstraní: zbylý soubor spouštěč smaže a program normálně spustí.
Postavíš ho přes `./build.sh`. Výsledné exe nahradí `TotalCommanderPortable.exe` v balíčku aplikace;
název aplikace a nastavení spouštěč čte za běhu z `App\AppInfo`. Ikona je obecná ikona PortableApps.
