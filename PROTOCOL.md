# Wire protocol

CamStream speaks the same TCP wire protocol as the official DroidCam app, so it works
as a drop-in replacement with the existing GPL-licensed PC-side pipeline
([dev47apps/droidcam-linux-client](https://github.com/dev47apps/droidcam-linux-client)).
No changes are needed on the PC side.

## Provenance

No DroidCam code is reused anywhere in this project. This protocol was independently
recovered by reading `droidcam-linux-client`'s own GPL-2.0 source (`src/av.c`,
`src/decoder.c`, `src/common.h`, `src/settings.c`), a codebase published specifically
so people can build interoperable clients. What follows is a description of an
interoperability wire format, not a copy of any implementation.

## Video stream

Plain TCP, no TLS, no auth, no session tokens.

**Connection model:** the phone runs a TCP server on port **4747** (the default; the
official app makes it user-configurable). The PC client makes an outbound connection to
`<phone-ip>:4747`; the phone is the *server*, the PC is the *client*.

**Step 1, request (PC → phone), plain ASCII, no trailing newline:**

```
CMD /v3/video/<codec>/<width>x<height>
```

- `<codec>` is `"jpg"` or `"avc"` (index 0/1 in the PC client's `codec_names[]`).
  CamStream currently implements `jpg` only, so no video encoder setup is needed on
  Android, since `YuvImage.compressToJpeg` handles it directly.
- `<width>x<height>` is the resolution the PC client *requests*. CamStream ignores
  this and always reports the resolution it's actually capturing (see Step 2).

**Step 2, response header (phone → PC), exactly 9 raw bytes:**

| Bytes | Meaning | Encoding |
|---|---|---|
| 0-1 | frame width | `uint16`, **big-endian** |
| 2-3 | frame height | `uint16`, **big-endian** |
| 4-8 | reserved / unused by the `jpg` decoder path | any value |

All 9 bytes must be sent even though only the first 4 are read: the PC client still
performs a blocking read of all 9 bytes and will stall waiting for the rest otherwise.

**Step 3, frame stream (phone → PC), repeated forever:**

| Field | Size | Encoding |
|---|---|---|
| frame length | 4 bytes | `uint32`, **little-endian** (note: opposite endianness from the header above) |
| frame data | `length` bytes | raw JPEG bytes (standard JFIF) |

No frame boundary markers beyond the length prefix.

**Optional control channel (PC → phone), not implemented:**

```
CMD /v1/ctl?<cmd_id>          (zoom, autofocus, flash, etc.)
CMD /v1/ctl?<cmd_id>=<value>
CMD /v1/stop
CMD /ping
```

These can arrive on the *same* socket at any point while frames are streaming.
CamStream doesn't parse anything past the initial request line: the server reads
that line with a short timeout rather than waiting on a delimiter (the request has no
trailing newline), then only ever writes to the socket afterward. Any control bytes
sent later just accumulate unread in the OS receive buffer, harmlessly.

**Audio** (`CMD /v2/audio`, Speex-encoded PCM) exists in the upstream protocol but is
not implemented here; see the "Not implemented" list in the README.

## License note

Because CamStream only *talks to* `droidcam-linux-client` over this network protocol,
with no source shared, linked, or bundled, its GPL-2.0 license doesn't extend to this
project. Protocol compatibility documentation like this file is exactly the kind of
interoperability information copyright law doesn't restrict.
