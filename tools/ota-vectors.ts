// Regenerates ios/WireKit/Tests/WireKitTests/ota-vectors.json from the site's own code: the image gate
// (ota-image.ts inspectOtaImage) on tiny synthetic AOTA images, and the byte stream Watch.flashFirmware (ble.ts)
// writes against a scripted watch. The Swift and Android ports must match both.
//   (cd ~/Developer/pet/fcm-group/fmc_frontend && bun $OLDPWD/tools/ota-vectors.ts) > ios/WireKit/Tests/WireKitTests/ota-vectors.json
// Run from the site's checkout: its tsconfig resolves $lib.
// the site's logging reads window.fmcBleVerbose; its dbg prints to console.log, which is the output here
(globalThis as { window?: object }).window = {};
const log = console.log;
console.log = console.debug = () => {};
const root = process.env.FMC_FRONTEND ?? `${process.env.HOME}/Developer/pet/fcm-group/fmc_frontend`;
const p = await import(`${root}/src/lib/modules/device/lib/ble-protocol.ts`);
const { CMD, Codec, UUID, crc32, fromHex, toHex, utf8 } = p;
const { inspectOtaImage } = await import(`${root}/src/lib/modules/device/lib/ota-image.ts`);
const { Watch } = await import(`${root}/src/lib/modules/device/lib/ble.ts`);

// ---- a tiny AOTA image (layout in ota-image.ts) ----
type Opts = {
  board?: string; xmlBoard?: string; parts?: [string, number][]; trailer?: boolean; xmlSize?: number;
  magic?: string; breakHeaderCrc?: boolean; breakDataCrc?: boolean; breakEntryCrc?: boolean;
};
const fill = (n: number, seed: number) => Uint8Array.from({ length: n }, (_, i) => (i * 13 + seed) & 0xff);
function image(o: Opts = {}): Uint8Array {
  const board = o.board ?? "jx402_01_3089c";
  const parts = o.parts ?? [["TEMP.bin", 1000], ["RES.bin", 300]];
  const xml = utf8(
    `<?xml version="1.0"?>\n<ota_firmware>\n<board_name>${o.xmlBoard ?? board}</board_name>\n` +
      `<version_code>0x10002</version_code>\n<partitions>\n` +
      parts.map(([n, s], i) => `<partition>\n<file_name>${n}</file_name>\n<file_size>${i ? (o.xmlSize ?? s) : `0x${(o.xmlSize ?? s).toString(16)}`}</file_size>\n</partition>\n`).join("") +
      `</partitions>\n</ota_firmware>\n`,
  );
  const blobs: [string, Uint8Array][] = [["ota.xml", xml], ...parts.map(([n, s], i) => [n, fill(s, i + 1)] as [string, Uint8Array])];
  const end = 0x400 + blobs.reduce((a, [, b]) => a + b.length, 0);
  const agps = o.trailer === false ? new Uint8Array(0) : new Uint8Array(24 + 40);
  const d = new Uint8Array(end + agps.length);
  const dv = new DataView(d.buffer);
  d.set(utf8(o.magic ?? "AOTA"), 0);
  dv.setUint32(0x0c, blobs.length, true);
  dv.setUint16(0x12, 0x400, true);
  dv.setUint32(0x14, end, true);
  d.set(utf8("1.00_2611180000"), 0x40);
  d.set(utf8(board), 0x60);
  let off = 0x400;
  blobs.forEach(([name, b], i) => {
    const e = 0x200 + i * 32;
    d.set(utf8(name), e);
    dv.setUint32(e + 16, off, true);
    dv.setUint32(e + 20, b.length, true);
    d.set(b, off);
    dv.setUint32(e + 28, crc32(b) ^ (o.breakEntryCrc && name === "TEMP.bin" ? 1 : 0), true);
    off += b.length;
  });
  if (agps.length) {
    agps.set(utf8("AGPS"), 0);
    new DataView(agps.buffer).setBigUint64(8, BigInt(agps.length - 24), true);
    d.set(agps, end);
  }
  dv.setUint32(0x18, crc32(d.subarray(0x400, end)) ^ (o.breakDataCrc ? 1 : 0), true);
  dv.setUint32(4, crc32(d.subarray(8, 0x400)) ^ (o.breakHeaderCrc ? 1 : 0), true);
  return d;
}
const cases: [string, Uint8Array][] = [
  ["ok", image()],
  ["watch_3_pro", image({ board: "jx402_08" })],
  ["unknown_board", image({ board: "jx999" })],
  ["no_magic", image({ magic: "ZOTA" })],
  ["header_crc", image({ breakHeaderCrc: true })],
  ["data_crc", image({ breakDataCrc: true })],
  ["entry_crc", image({ breakEntryCrc: true })],
  ["xml_board", image({ xmlBoard: "jx402_08" })],
  ["xml_size", image({ xmlSize: 5 })],
  ["no_temp", image({ parts: [["RES.bin", 300]] })],
  ["no_trailer", image({ trailer: false })],
  ["too_small", new Uint8Array(100)],
];
const verdict = (d: Uint8Array) => {
  try {
    const i = inspectOtaImage(d);
    return { board: i.board, version: i.version, version_code: i.versionCode };
  } catch (e) {
    return { refuse: (e as Error).message };
  }
};

// ---- flashFirmware against a scripted watch ----
const key = fromHex("000102030405060708090a0b0c0d0e0f");
const img = image();
const watchCodec = new Codec();
await watchCodec.setKey(key);
const name = (u: string) => ({ [UUID.cmdWrite]: "fff2", [UUID.otaWrite]: "ff01" })[u] ?? u;
const writes: [string, string][] = [];
const notes: [string, string][] = [];
const w = new Watch();
await w.codec.setKey(key);
const notify = async (uuid: string, k: number, payload: Uint8Array) => {
  for (const f of await watchCodec.encode(k, payload)) {
    notes.push([uuid === UUID.otaRead ? "ff02" : "fff1", toHex(f)]);
    await w.onNotify(uuid, f);
  }
};
const range = (off: number, len: number, pct?: number) => {
  const r = new Uint8Array(pct === undefined ? 8 : 9);
  const dv = new DataView(r.buffer);
  dv.setUint32(0, off);
  dv.setUint32(4, len);
  if (pct !== undefined) r[8] = pct;
  return r;
};
// what the watch asks for, in any order and only what differs (docs/ota.md); one past the end is ignored
const script = async () => {
  await notify(UUID.otaRead, CMD.otaStartRep, new Uint8Array([1]));
  for (const r of [range(0, 300, 5), range(600, 458, 40), range(300, 300, 60), range(img.length - 50, 500, 90),
    range(img.length + 10, 4, 95), range(1024, 224)])
    await notify(UUID.otaRead, CMD.otaChunkReq, r);
  await notify(UUID.otaRead, CMD.otaVerdict, new Uint8Array([1]));
};
const writer = (uuid: string) => async (f: Uint8Array) => {
  writes.push([name(uuid), toHex(f)]);
  const frame = await watchCodec.decode(f).catch(() => null);
  if (frame?.key === CMD.otaBeginReq) setTimeout(() => notify(UUID.cmdRead, CMD.otaBeginRep, new Uint8Array([1])));
  if (frame?.key === CMD.otaStartReq) setTimeout(script);
};
const char = (uuid: string) => ({ writeValueWithoutResponse: writer(uuid), writeValueWithResponse: writer(uuid) });
w.chars = { [UUID.cmdWrite]: char(UUID.cmdWrite), [UUID.otaWrite]: char(UUID.otaWrite), [UUID.otaRead]: {} } as never;
const progress: number[] = [];
await w.flashFirmware(img, (pct: number) => progress.push(pct));

log(JSON.stringify({
  images: cases.map(([name, d]) => ({ name, hex: toHex(d), ...verdict(d) })),
  flash: { key: toHex(key), image: toHex(img), notifications: notes, writes, progress },
}, null, 1));
