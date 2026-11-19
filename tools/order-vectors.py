# order-vectors.json: the Apps menu order in the loader package (cmf-watch-sdk docs/app-order.md), made by the SDK's
# own package.set_order. Both apps' tests read it (WireKit setOrder, Android wire setOrder).
#   python3 tools/order-vectors.py > ios/WireKit/Tests/WireKitTests/order-vectors.json
# CMF_SDK: the cmf-watch-sdk checkout with docs/app-order.md (default ~/Developer/pet/cmf-watch-sdk).
import json, os, sys

sys.path.insert(0, os.path.join(os.path.expanduser(os.environ.get("CMF_SDK", "~/Developer/pet/cmf-watch-sdk")), "src"))
from wp2apps import package as p  # noqa: E402

head = bytes(range(32)) * 2
tail = bytes(range(200, 256)) * 2


def loader(image):
    return p.pack(image, name="loader", version=1, entry=0, kind=p.KIND_LOADER, relocs=[4, 8])


plain = loader(head + tail)
ordered = loader(head + p.ORDER_MAGIC + bytes(24 * p.ORDER_MAX) + tail)
twice = loader(head + p.ORDER_MAGIC + bytes(24 * p.ORDER_MAX) + p.ORDER_MAGIC + bytes(24 * p.ORDER_MAX) + tail)
cut = loader(head + p.ORDER_MAGIC + bytes(100))

ok = [["vibration-lab", "hello"], [], ["погода", "a" * 23], [f"app{i}" for i in range(16)]]
bad = [(plain, ["hello"], "no WP2ORDER: a loader older than the app order"),
       (twice, ["hello"], "WP2ORDER twice"),
       (cut, ["hello"], "the table runs past the image"),
       (ordered, [f"app{i}" for i in range(17)], "17 names"),
       (ordered, ["a" * 24], "a name of 24 bytes"),
       (ordered, [""], "an empty name")]
out = {
    "loader": ordered.hex(),
    "ok": [{"names": n, "hex": p.set_order(ordered, n).hex()} for n in ok],
    "bad": [{"loader": l.hex(), "names": n, "note": why} for l, n, why in bad],
}
for b in bad:
    try:
        p.set_order(b[0], b[1])
        raise SystemExit(f"set_order took: {b[2]}")
    except ValueError:
        pass
print(json.dumps(out, indent=1, ensure_ascii=False))
