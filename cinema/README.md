# Kirazium Android Cinema

KiraziumClient mobil sinema katmani DreamDisplays 1.9.5 / Minecraft 26.1.2 tabanlidir.

Bu depo launcher, gomulu FFmpeg ve mobil sinema modunu tek Android istemcisinde birlestirir. Ayrica helper APK gerekmez.

## Dogrulanmis hedef

- Decode: software
- DreamDisplays JAR: `dreamdisplays-fabric-26.1.2-1.9.5-kirazium-android-stallfix1.jar`
- Hedef boyut: `23595235` byte
- Hedef SHA-256: `918872694b9fe437b6c412dda287e717d33b654a1bf8885af0ceea0ed38caab1`
- Watchdog: stall 15000 ms, startup-progress 20000 ms, startup-hard 45000 ms
- Optimizasyonlar: duplicate-load-dedupe, android-init-cap, audio-warm-pool-off, bounded-discard-workers, decoder-progress-watchdog, pre-roll-safe-startup

## Exact reconstruction

Telefon-testli stallfix1 JAR, daha once dogrulanmis public Android9 tabani ile exact bsdiff kullanilarak byte-byte yeniden uretilir.

- Android9 base size: `23437862`
- Android9 base SHA-256: `9525319933e1fb629cd151b37326e7dbba88286a1db3d742dba7277d4070f935`
- bsdiff size: `599883`
- bsdiff SHA-256: `9ab36322303a8582d76c083bd34a777728c0057eb4deed22319e95b1a48ae9bd`
- target SHA-256: `918872694b9fe437b6c412dda287e717d33b654a1bf8885af0ceea0ed38caab1`

`reconstruct-stallfix1.sh` bu degerlerden biri uyusmazsa islemi aninda durdurur.

Launcher tarafinda `KiraziumBootstrap.ensureEmbeddedCinemaMod()` bu JAR'i APK asset'inden oyun `mods` klasorune otomatik kurar ve eski DreamDisplays JAR'larini temizler.
