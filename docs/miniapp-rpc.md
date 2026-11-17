# Miniapp services: RPC (draft)

A miniapp on the watch asks the phone for data the watch cannot reach: the phone's calendar, location, weather,
notifications, a web request. The phone answers from a fixed set of services written natively in both apps. No code
from the site runs on the phone. The first time an app calls a service, the phone asks the user; the answer is kept
per app.

Status: draft, being implemented. Byte layouts are fixed by `ios/WireKit/Tests/WireKitTests/rpc-vectors.json`, which
both apps test against (as `vectors.json`). A change to a vector is a change for both apps and the watch apps.

## Constraints this design follows

From cmf-watch-sdk (`docs/app-guide.md` §4, `docs/abi-1.4.md`):

- App messages are 1–128 bytes each way; the watch app has an inbox and an outbox of 4 messages each.
- Host → app goes to the running app only (`MSG`, slot `0xFF`); `NO_APP` otherwise. So the watch pulls: a closed
  miniapp gets nothing pushed.
- App → host arrives as `EVENT`, plain `seq ‖ slot ‖ data` (loader of 2026-11-13+). `slot` tells the phone which app
  asked.
- `text` draws at most 64 bytes; glyphs cover ASCII and Cyrillic only.
- The app has local wall-clock time (`time()`), so times go over the wire as local day + minute, no epoch or zones.
- **Anyone in BLE range can read K and with it every app message.** The SDK says not to send personal data. See
  "Personal data".
- iOS: the companion runs in the background only while woken by BLE (`bluetooth-central`), about 10 s per wake.
  Every method answers within 8 s. Whether a notify on the link Nothing X holds wakes the app at all is checked by the
  DEBUG Wake probe (`ios/App/WakeProbe.swift`) and not yet verified on hardware.

## Who is asking

The phone keeps the `slot` of each `EVENT` and maps it to the package name in that slot's `WP2A` header (`INFO`,
as `readLoader` reads it; cached, read again for a slot it has not seen or after an install). Grants are keyed by the
package name.

ponytail: a package that takes another's name gets its grants. Fine while the owner installs every package; a
signature in the package header when the store installs apps for others.

## Permissions

Capabilities:

| Cap | Methods | OS permission |
|---|---|---|
| (none) | `caps` | — |
| `calendar` | `calendar.events` | EventKit full access (iOS 17+ `requestFullAccessToEvents`); `READ_CALENDAR` |
| `location` | `location` | When In Use, reduced accuracy is enough; `ACCESS_COARSE_LOCATION` |
| `weather` | `weather` | the same as `location` (the phone needs it; the watch gets no position) |
| `http:<host>` | `http.get` to that host | — |
| `notifications` | `notifications` | Android: notification listener access. iOS: none possible, the method answers `UNAVAILABLE` |

Flow:

1. A method whose cap this package has no answer for yet: reply `ASK`. The phone keeps a pending request
   (package, cap) and posts a local notification "‹package› wants ‹cap›", at most once a day per pair.
2. The companion's Permissions screen (opened from that notification or from Settings) lists pending requests and
   every package's grants. Allow asks for the OS permission the cap needs, if it is not given yet; Deny is kept too.
3. Later calls: granted and OS permission on → the method runs; denied, or the OS permission is off → `DENIED`, and no
   new notification.
4. Each grant can be revoked on the same screen. Per package there is also one switch, "Show text on the watch"
   (off by default): calendar titles and notification text. See "Personal data".

A manifest on fmc.tools that declares caps ahead (a consent screen at install) can come later, when the site has a
collection for apps; it would only pre-fill these grants.

## Messages

All integers little-endian. Every RPC message opens with `R1`, so a miniapp keeps its own messages (any other prefix)
on the same channel. Only `R1Q` is a request; any other `R1x` is dropped. An `R1Q` the phone cannot read (no method
byte, unknown method, bad args) gets `BAD_REQUEST` if it has an id byte, else it is dropped.

Request (watch → phone):

```
"R1" ‖ 'Q' ‖ id:u8 ‖ method:u8 ‖ args
```

Reply (phone → watch):

```
"R1" ‖ 'A' ‖ id:u8 ‖ status:u8 ‖ index:u8 ‖ total:u8 ‖ record
```

- `id` is the app's counter; the phone echoes it. The app ignores replies whose `id` is not its latest.
- A result of `total` records goes as `total` messages, `index` 0…total−1, one record each (≤ 121 bytes), sent with
  `KSession.send` (it retries `FULL`/`PAUSED`), so the app drains `recv` in its loop.
- Empty result or any error: one message, `index` 0, `total` 0, no record.
- The phone handles one request at a time, in order of arrival, and keeps no state per request.
- No answer at all (phone asleep, out of range, companion not running): the app shows its cached copy (`store`,
  4 KiB) and asks again after 3 s, then less often.

| `status` | |
|---|---|
| 0 `OK` | |
| 1 `BAD_REQUEST` | unknown method, bad args |
| 2 `DENIED` | the user denied the cap, or its OS permission is off |
| 3 `UNAVAILABLE` | the phone cannot answer now: no location fix, network down or timeout, iOS notifications |
| 4 `ASK` | the user has not answered yet; the phone asked. Show "Allow on your phone" |
| 5 `TOO_LARGE` | `http.get` body over 2048 bytes |

Text fields are UTF-8, cut by the phone to at most the stated size, at a character boundary (`utf8_cut` vectors:
64 Cyrillic-only bytes stay 64).
Characters the watch has no glyph for stay as they are (they draw as `?`).

## Methods

| Id | Method | Cap |
|---|---|---|
| `0x01` | `caps` | — |
| `0x10` | `calendar.events` | `calendar` |
| `0x20` | `location` | `location` |
| `0x30` | `weather` | `weather` |
| `0x40` | `http.get` | `http:<host>` |
| `0x50` | `notifications` | `notifications` |

### `0x01 caps`

Args: none. One record: the method ids this package may call now (granted and OS permission on), one byte each,
without `0x01` itself; `0x40` when any host is granted.
A miniapp calls it at start to tell "allow on your phone" from an empty result.

### `0x10 calendar.events`

Args: `from_day:i8 ‖ days:u8 ‖ limit:u8`. `from_day` 0 = today (phone's local date), `days` 1–7, `limit` 1–20;
out-of-range values are clamped. Events from the calendars visible in the system calendar app, sorted by start,
all-day events first within a day.

Record (`calendar`, `calendar_dates` vectors):

```
day:i8 ‖ start:u16 ‖ duration:u16 ‖ flags:u8 ‖ color:u16 ‖ title
```

| Field | |
|---|---|
| `day` | days from today, phone's local date |
| `start` | minute of the day, local; 0 for all-day |
| `duration` | real minutes from start to end (60 for 01:30–03:30 on a spring-forward day); all-day: 1440 × days |
| `flags` | bit 0 all-day, bit 1 tentative, bit 2 text hidden |
| `color` | calendar colour, RGB565 |
| `title` | ≤ 64 bytes; empty with bit 2 |

### `0x20 location`

Args: none. One record (`location` vectors):

```
lat:i16 ‖ lon:i16 ‖ age:u16 ‖ place
```

`lat`, `lon` in 0.1° (about 10 km), rounded. `age` minutes since the fix, `0xFFFF` for older. `place` the locality
("Москва"), ≤ 32 bytes, may be empty. The phone uses its last known location and refreshes it while the companion
is in the foreground or when asked and allowed; no fix at all: `UNAVAILABLE`.

### `0x30 weather`

Args: `days:u8`, 0–7: daily records for days 0…days−1, after the current one. Records (`weather` vectors):

```
current: 0:u8 ‖ temp:i16 ‖ code:u8 ‖ wind:u8 ‖ humidity:u8 ‖ age:u8
daily:   1:u8 ‖ day:i8 ‖ min:i8 ‖ max:i8 ‖ code:u8 ‖ precip:u8
```

`temp` in 0.1 °C, `min`/`max` in °C, `wind` m/s, `humidity` and `precip` (probability) in %, `age` minutes since the
data was fetched (capped at 255), `day` 0 = today. `code` is a WMO weather code as Open-Meteo gives it (0 clear;
1–3 clouds; 45, 48 fog; 51–57 drizzle; 61–67 rain; 71–77 snow; 80–82 showers; 85, 86 snow showers; 95–99
thunderstorm). Source: WeatherKit on iOS (conditions mapped to these codes), Open-Meteo on Android. The phone caches
for 30 min and fetches with a 6 s timeout; nothing cached and no network: `UNAVAILABLE`.

### `0x40 http.get`

Args: the URL, UTF-8, `https://` only, ≤ 123 bytes. Cap `http:<host>` for the URL's host. GET without cookies, with
`User-Agent: FMC-Companion`, 6 s timeout, redirects only within the same host. Records (`http` vectors):

```
first: code:u16 ‖ length:u16
then:  the body in chunks of 121 bytes
```

Any HTTP code is `OK` with that code; a body over 2048 bytes is `TOO_LARGE`; no answer in time or no network:
`UNAVAILABLE`. The app's own server should answer compactly: the watch has 4 KiB of storage.

### `0x50 notifications`

Android only; iOS answers `UNAVAILABLE`. Args: `limit:u8`, 1–10. Records, newest first (`notifications` vectors):

```
age:u16 ‖ flags:u8 ‖ app_len:u8 ‖ app ‖ text
```

The notifications shown now, without ongoing ones (music, navigation) and the companion's own. `age` minutes since
posted, `flags` bit 2 text hidden, `app` the app's label ≤ 24 bytes, `text` "title: text" ≤ 64 bytes, empty with
bit 2.

## Personal data

Calendar titles, notification text and location are what the SDK says never to send: K is readable by any BLE
client, so anyone in range can read them off the air.

- Titles and notification text: off by default (bit 2, empty text); the per-package "Show text on the watch" switch
  turns them on, with a line saying others nearby could read them.
- Location: only 0.1° and the locality, and only after the user allowed `location` for that package, with the same
  line on the permission screen.
- Never sent: event locations, attendees, notes; notification senders' details beyond the text; precise coordinates.

A real fix needs a key the watch and phone share that does not leave over the air; out of scope here.
