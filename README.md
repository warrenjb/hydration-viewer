# Hydration Viewer

Reads water intake from Health Connect (Hidrate Spark writes there directly)
and, since neither WaterH nor the Thermos Connected Hydration Bottle use
Health Connect, talks to both bottles directly over Bluetooth Low Energy and
writes their drinks into Health Connect itself. Shows today's total, a 7-day
stacked bar chart (colored by source), and a combined recent-entries list.

## How WaterH sync works

WaterH's own app syncs to Google's legacy Fit API (being shut down in 2026),
not Health Connect — so there's no way to read its data through Health
Connect. Instead, this app:

1. Scans for a nearby BLE device whose name starts with "WATERH".
2. Connects and subscribes to notifications on its drink-log characteristic
   (service `0xFFE0`, characteristic `0xFFE4`).
3. Parses each 13-byte record: date, time, and volume in mL (byte offset 7).
   This format was reverse-engineered from real captured data, not a public
   spec — if the bottle's firmware changes, this may need re-verifying with
   nRF Connect the same way it was worked out originally.
4. Writes each drink into Health Connect as a `HydrationRecord`, using a
   `clientRecordId` derived from its timestamp so re-syncing the same
   history never creates duplicates.

## How Thermos sync works

The Thermos Smart Lid bottle doesn't push data on its own the way WaterH
does — subscribing to its notify/indicate characteristics in nRF Connect and
just sipping produced nothing. Getting its history took two rounds of
reverse-engineering:

1. A first Bluetooth HCI snoop capture (Settings → Developer options →
   Enable Bluetooth HCI snoop log, decoded by hand with a small Python
   parser, no Wireshark/tshark available) showed the real app writing two
   16-byte values to `64ac0002`/`64ac0004` before the bottle would accept
   commands on `40fc0003`. Replaying those exact bytes got flatly rejected
   (`GATT status 133`) — because:
2. A *second* capture showed those bytes are different every connection —
   it's a real AES-128 challenge-response handshake, not a static token.
   Decompiling the real Thermos app's APK (`com.thermos.smartlid`, pulled
   via `adb` and run through jadx) found the exact algorithm in
   `com.idevicesinc.sweetblue.AuthTransaction`, plus a hardcoded AES key in
   `DashboardFragment`. Decrypting the second capture's real challenge with
   that key and recomputing the response reproduced the real app's bytes
   exactly, confirming both the algorithm and the key are correct:
   - Generate 8 random bytes, zero-pad to 16 ("app challenge"), write to
     `64ac0002`.
   - Read `64ac0003` ("device challenge") — 16 bytes, AES-128/ECB/NoPadding
     encrypted with that key.
   - Decrypt it; the first 8 bytes must equal the random bytes just sent
     (proves the bottle knows the key).
   - Build a response: 8 zero bytes + the *second* 8 bytes of that
     decrypted plaintext, encrypt it with the same key, write it to
     `64ac0004`.
3. Only now does the bottle accept writes to `40fc0003`, the command
   channel: a far-past "since" epoch, the phone's current Unix time, and a
   3-byte trigger (`03 01 <tz>`, where `<tz>` is the local UTC offset folded
   into 0-23, matching the real app's own midnight-offset calculation).
4. That round alone doesn't make the bottle talk — confirmed from the same
   real capture, where the real app sends this exact sequence once and gets
   silence too. It's only once the "since" command (`00 04 <since>`) is
   written a *second* time that the first batch of `40fc0004` indications
   actually arrives.
5. The bottle replies with 8-byte indications on `40fc0004`: a little-endian
   Unix timestamp, a group id, a sub-index, a fill-level percent (0-100),
   and a roughly-stable battery-ish byte. Each further batch is gated
   behind a plain notify on `40fc0001` (live fill level) — the bottle's
   "there's more" signal — which the app answers by re-sending `00 04
   <timestamp of the last record just received>` to pull the next batch,
   repeating until the bottle stops notifying.

There's no explicit "volume drunk" field — sips are inferred from the *drop*
in fill-level percent between consecutive readings, scaled against the
bottle's capacity (`ThermosBleClient.BOTTLE_CAPACITY_ML`, defaulting to
~950 mL / 32 oz — adjust if your bottle is a different size and the sip
sizes look off).

## Setup

1. Open this folder in Android Studio, let Gradle sync.
2. Run on your phone. The main screen is just today's total, the 7-day
   stacked chart, and the entry list — it re-reads from Health Connect
   every time it comes back on screen, so it always reflects your latest
   sync without a manual refresh button.
3. Tap **Settings** (top right) for everything else:
   - **Grant Health Connect permission** first (requests both read and
     write).
   - **Connect WaterH Bottle & Sync** — grant the Bluetooth permission
     prompt. Make sure the WaterH app itself is closed, since BLE devices
     usually only accept one connected app at a time. Bring the bottle in
     range; status text will show scanning → connected → listening. Take a
     sip, or wait for it to push its history on connect.
   - **Connect Thermos Bottle & Sync** the same way for the Thermos bottle
     — close the real Thermos app first for the same reason.
4. Back out of Settings to see the updated chart.

## Known limitations

- The WaterH record format was inferred from a handful of real samples
  cross-checked against WaterH's own displayed totals — treat any single
  value with some skepticism, especially bytes we didn't confirm (the
  constant tail bytes).
- The Thermos auth handshake implements the real algorithm from the
  decompiled app (AES-128/ECB with the app's own hardcoded key), verified
  against a real captured challenge/response pair — not a guess. It should
  keep working unless Thermos changes that key or algorithm in a future app
  update, in which case the symptom would be "Authentication failed —
  bottle's challenge didn't match our nonce" in the status text.
  - Sip size is inferred from fill-level *percent* drops, not a native mL
    field — accuracy depends on `BOTTLE_CAPACITY_ML` matching your actual
    bottle.
  - `40fc0002` (battery) and `40fc0005` (unknown) are logged raw in Logcat
    but not yet used for anything.
- BLE notifications only arrive while this app is connected and in range for
  either bottle — there's no background sync yet, unlike the Hidrate Spark
  side which flows through Health Connect automatically.
- If either bottle's firmware updates, the byte layouts could change without
  warning.
