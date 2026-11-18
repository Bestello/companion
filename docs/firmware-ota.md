# Firmware update (stock OTA)

The Firmware page installs fmc.tools' `patched` firmware over the stock OTA: WireKit `Ota.swift`, Android
`wire/Ota.kt`, ports of fmc_frontend `ota-image.ts` and `ble.ts` `flashFirmware`. `tools/ota-vectors.ts` records the
site's verdicts and written bytes into `ota-vectors.json`, which both test suites replay.

## Emulator run (2026-11-18)

The Android app, unmodified apart from two lines that show the Firmware page and its Install button without a
connected Watch tab, flashed the published image (`1.0.0.73-wp2.8`, stub 5, 57 MB) into the virtual watch of
cmf-watch-sdk:

- the watch pulled 943 ranges (header, ota.xml, the AGPS trailer, TEMP.bin), the app's percent matched the
  watch's own, then `d2h_end 1` (image valid), commit, `ota success` and the OTA reboot;
- `emu recover` installed the staged update; the resulting NOR answers `wp2apps list` with stub 5, the original
  `paired-nor.bin` has no stub.

A first run broke at the watch's 30% because the Android emulator hung and dropped the link: the watch dropped the
update (`ota state 1->3`, then `OTA_INIT`) and installed nothing.

How:

1. `emulator -avd <avd> -no-window -no-snapshot-load -netsim-args '--hci-port 17310'`: the emulator starts
   `netsimd` with that H4 port. With a window it hung after ~10 minutes, twice.
2. Install the app with the two lines changed: `FirmwareScreen`'s `offer = true`, and the Watch tab's Firmware row
   shown without `Phase.Checked`. The Watch tab can't read the virtual watch: it takes only a connected or bonded
   watch, and the virtual one doesn't bond (Settings › Pair new device stays at "Pairing…"). The OTA scans for it.
3. In cmf-watch-utils: `emu run … --nor <cmf-watch-sdk>/runs/e2e/paired-nor.bin --headless --allow-reboot
   --ota-snapshot --hci-port 17310 --qmp-port 17320 --output <out>/stage`, then `emu recover` as in
   cmf-watch-sdk `scripts/emu-ota`.
4. Keep the virtual screen on, or stock suspends into S3BT (which this QEMU doesn't model) after 15 s:
   `while sleep 5; do printf 't 1 233 233\nt 0 233 233\n' >> <out>/stage/input.txt; done`. Stop it while a
   host binds (`wp2apps list`), or the taps confirm the bind popup.
5. Firmware › Install › Install. About 10 minutes over Netsim.
