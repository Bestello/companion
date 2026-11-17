# Miniapp services: manifest and RPC (draft)

A miniapp on the watch asks the phone for data the watch cannot reach (the phone's calendar first). The phone
answers from a fixed set of services written natively in both apps. No code from the site runs on the phone: a
miniapp only declares which services it uses, the user grants them at install, and the watch calls them over the
existing app channel.

Status: draft. Nothing here is implemented. Version 1 has two methods: `caps` and `calendar.events`.

## Constraints this design follows

From cmf-watch-sdk (`docs/app-guide.md` §4, `docs/abi-1.4.md`):

- App messages are 1–128 bytes each way; the watch app has an inbox and an outbox of 4 messages each.
- Host → app goes to the running app only (`MSG`, slot `0xFF`); `NO_APP` otherwise. So the watch pulls: a closed
  miniapp gets nothing pushed.
- App → host arrives as `EVENT` (`seq ‖ slot ‖ data`) without polling (loader of 2026-11-13+). `slot` tells the phone
  which app asked.
- `text` draws at most 64 bytes; glyphs cover ASCII and Cyrillic only.
- The app has local wall-clock time (`time()`), so times go over the wire as local day + minute, no epoch or zones.
- **Anyone in BLE range can read K and with it every app message.** The SDK says not to send personal data. See
  "Personal data" below: calendar titles are personal data.
- iOS: the companion runs in the background only while woken by BLE (`bluetooth-central`). Whether a notify on the
  link Nothing X holds wakes it is **not verified on hardware**; check that before building on it.

## Manifest

Lives on the miniapp's record on fmc.tools (a JSON field next to the `.wp2app` file), read by the companion before
install:

```json
{
  "package": "calendar",
  "version": 3,
  "uses": [
    { "cap": "calendar.read", "why": "Ближайшие события на часах" }
  ]
}
```

| Field | Rule |
|---|---|
| `package` | equals `name` in the package's `WP2A` header (≤ 23 bytes). The phone keys grants by it |
| `version` | equals the header `version`. The companion refuses a file whose header does not match the record |
| `uses[].cap` | a capability from the table below. Unknown cap: the companion refuses the install and asks for an update of itself |
| `uses[].why` | one line shown on the consent screen, ≤ 80 chars |

Capabilities in v1:

| Cap | Methods | OS permission |
|---|---|---|
| (none) | `caps` | — |
| `calendar.read` | `calendar.events` | EventKit full access (iOS 17+: `requestFullAccessToEvents`), `READ_CALENDAR` |

Install flow:

1. Companion fetches the record, checks `package`/`version` against the downloaded header.
2. If `uses` is not empty: consent screen listing each cap with its `why`. Declined: no install.
3. OS permission prompt for each cap that needs one. Denied: install anyway, the method answers `DENIED` and the
   miniapp says "allow in the phone app".
4. Grants stored on the phone as `package → [cap]`. An update whose `uses` adds a cap goes back to step 2; removing the
   app from the watch drops its grants. The companion's settings list every package's grants with a switch each.

## RPC

All integers little-endian. Every RPC message opens with the magic `R1`, so a miniapp can keep its own messages
(any other prefix) on the same channel.

### Request (watch → phone)

```
"R1" ‖ 'Q' ‖ id:u8 ‖ method:u8 ‖ args
```

`id` is the app's counter; the phone echoes it. The app ignores replies whose `id` is not the latest one it sent.

### Reply (phone → watch)

```
"R1" ‖ 'A' ‖ id:u8 ‖ status:u8 ‖ index:u8 ‖ total:u8 ‖ record
```

- A result of `total` records goes as `total` messages, `index` = 0…total−1, in order, one record each
  (≤ 121 bytes). The phone sends them with `KSession.send`, which already retries `FULL`/`PAUSED`, so the app just
  drains `recv` in its loop.
- Empty result: one message, `status` 0, `index` 0, `total` 0, no record.
- Error: one message, `index` 0, `total` 0, no record.

| `status` | Meaning |
|---|---|
| 0 `OK` | |
| 1 `BAD_REQUEST` | unknown method, bad args |
| 2 `DENIED` | the package has no grant for the method's cap, or the OS permission is off |
| 3 `UNAVAILABLE` | the phone cannot answer right now (e.g. calendar store not ready) |

No answer at all (phone asleep, out of range, companion not running): the app keeps showing its cached copy
(`store`, 4 KiB) and asks again after 3 s, then less often. The app owns retries; the phone keeps no state per
request.

### Methods

#### `0x01 caps`

Args: none. One record: the method ids this package may call now (granted and OS permission on), one byte each.
A miniapp calls it once at start to show "allow in the phone app" instead of an empty screen.

#### `0x10 calendar.events`

Args:

```
from_day:i8 ‖ days:u8 ‖ limit:u8
```

`from_day` 0 = today (phone's local date), `days` 1–7, `limit` 1–20. The phone clamps out-of-range values instead
of failing. Events from the calendars selected in the companion (default: all visible), sorted by start, all-day
events first within a day.

Record:

```
day:i8 ‖ start:u16 ‖ duration:u16 ‖ flags:u8 ‖ color:u16 ‖ title
```

| Field | |
|---|---|
| `day` | days from today, phone's local date |
| `start` | minute of the day, local; 0 for all-day |
| `duration` | minutes; an event past midnight keeps its full length (the app clips) |
| `flags` | bit 0 all-day, bit 1 tentative, bit 2 title hidden |
| `color` | calendar colour as RGB565 |
| `title` | UTF-8, the rest of the message, ≤ 64 bytes (one `text` call), cut by the phone at a character boundary. Characters the watch has no glyph for are left as they are (they draw as `?`). Empty with flag bit 2 |

Example: today 09:30–10:00, untitled ("title hidden"), red, reply 0 of 1 to request 7:

```
52 31 41 07 00 00 01   00 3A 02 1E 00 04 00 F8
```

## Personal data

Calendar titles are what the SDK says never to send: K is readable by any BLE client, so anyone in range can read
them off the air. v1 therefore sends **no titles by default** (flag bit 2, empty title): time, length and colour
only. A per-app switch in the companion, "Show event titles on the watch", turns titles on, with a line saying
others nearby could read them (decided 2026-11-17). Locations, attendees and notes are never sent.

A real fix needs a key the watch and phone share that does not leave over the air; out of scope here.

## Tests

`ios/WireKit/Tests/WireKitTests/rpc-vectors.json`, read by both apps as `vectors.json` is: request bytes → parsed
request, and (events, clock, timezone) → reply bytes, including a title cut mid-Cyrillic at 64 bytes, an event across
midnight and a DST day.

## Later, not in v1

- More services (`weather`, `http.get` with a domain allow-list in the manifest, `music.control`) follow the same
  pattern: a cap, its methods, a record layout.
- A phone-side script per miniapp (sandboxed JS with only these services as its API) if a miniapp ever needs logic
  the fixed services cannot express.
