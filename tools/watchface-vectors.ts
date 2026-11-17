// Regenerates ios/WireKit/Tests/WireKitTests/watchface-vectors.json from the site's own code, so the Swift (and
// Android) port of the stock watchface upload stays byte-equal to fmc.tools.
//   FMC_FRONTEND=~/Developer/pet/fcm-group/fmc_frontend bun tools/watchface-vectors.ts > ios/WireKit/Tests/WireKitTests/watchface-vectors.json
const root = process.env.FMC_FRONTEND ?? `${process.env.HOME}/Developer/pet/fcm-group/fmc_frontend`;
const p = await import(`${root}/src/lib/modules/device/lib/ble-protocol.ts`);
const { CMD, Codec, cat, dialId, fromHex, sha256, toHex, utf8 } = p;

const key = fromHex("000102030405060708090a0b0c0d0e0f");
const codec = new Codec();
await codec.setKey(key);
const bytes = (n: number) => Uint8Array.from({ length: n }, (_, i) => (i * 7 + 3) & 0xff);
const enc = async (k: number, payload: Uint8Array) => ({
  c1: (k >>> 16) & 0xffff, c2: k & 0xffff, payload: toHex(payload),
  frames: (await codec.encode(k, payload)).map(toHex),
});

const secret = "00112233445566778899aabbccddeeff";
const random1 = bytes(16), random2 = fromHex("f0e0d0c0b0a090807060504030201000");
const s = fromHex(secret);
const k1 = (await sha256(cat(random1, random2, s))).slice(0, 16);
const nonce = fromHex("0a0b0c0d0e0f10111213141516171819");

// tiny synthetic faces: screens (tag + child tags) and image sizes, judged by the site's own gate
const bin = await import(`${root}/src/lib/modules/editor/core/format/bin.ts`);
const tlv = (tag: number, v: Uint8Array) => cat(new Uint8Array([tag, v.length & 0xff, v.length >> 8]), v);
const face = (screens: [number, number[]][], images: [number, number][]) => {
  const hdr = new Uint8Array(36);
  hdr.set([1, 0, 0, 0], 4);
  hdr.set(utf8("Test"), 8);
  const tree = cat(...screens.map(([t, subs]) => tlv(t, cat(...subs.map((c) => tlv(c, new Uint8Array([1, 2]))), new Uint8Array(0)))), new Uint8Array(0));
  const res = images.map(([w, h]) => {
    const r = new Uint8Array(12);
    new DataView(r.buffer).setUint32(0, (w << 10) | (h << 21) | 1, true);
    new DataView(r.buffer).setUint32(4, 4, true);
    return r;
  });
  return cat(hdr, tlv(0x20, tree), ...res, hdr);
};
const verdict = (b: Uint8Array) => {
  try { bin.assertFlashable(b); } catch (e) { return { refuse: (e as Error).message }; }
  return { confirm: bin.flashWarning(b) };
};
const cases = [
  ["ok", face([[0x21, [0x28, 0x30]], [0x22, [0x28]]], [[466, 466], [100, 50]])],
  ["aod_no_preview", face([[0x21, [0x28]], [0x22, [0x30]]], [[466, 466]])],
  ["main_no_preview", face([[0x21, [0x30]]], [[466, 466]])],
  ["too_big", face([[0x21, [0x28]]], [[1386, 1386]])],
  ["needs_confirm", face([[0x21, [0x28]]], [[576, 530]])],
] as const;

console.log(JSON.stringify({
  faces: cases.map(([name, b]) => ({ name, hex: toHex(b), ...verdict(b), max_side: bin.MAX_IMAGE_SIDE, safe_side: bin.SAFE_IMAGE_SIDE })),
  mtu: codec.mtu,
  key: toHex(key),
  encode: [
    await enc(CMD.authName, cat(new Uint8Array([0xa5]), utf8("fmc"))),
    await enc(CMD.wfInit2Req, bytes(300)),
    await enc(CMD.wfChunkWrite, bytes(400)),
    await enc(CMD.wfInit1Req, new Uint8Array(0)),
  ],
  pair: {
    secret, random1: toHex(random1), random2: toHex(random2),
    request: toHex(cat(random1, await sha256(cat(random1, s)))),
    reply: toHex(cat(random2, await sha256(cat(random2, s)))),
    k1: toHex(k1), nonce: toHex(nonce),
    session: toHex((await sha256(cat(nonce, k1))).slice(0, 16)),
  },
  dial_id: Object.fromEntries(["abc123", "My Face", "rec_9x0q1w2e3r4t5y6"].map((x) => [x, dialId(x)])),
}, null, 1));
