# Pulse Relay

A minimal Wear OS app for the Samsung Galaxy Watch 7 that streams the current
heart rate to a WebSocket server such as the one on [my website](https://menal.net).

> [!NOTE]
> This app is vibecoded and was only tested on my own device.

## What it does

- Reads heart rate and sends the latest valid value every interval.
- Keeps running in the background with the screen off.
- Lets you set the endpoint, Bearer token, and interval on the watch.
- Reconnects only when internet is available. Readings while disconnected are
  dropped; there is no queue, batching, or replay.
- Doesn't send any messages when there is no current reading.

The interval can be 1 second to 1 hour. The default is 1 second.

## Setup

1. Install the APK (usually using adb).
2. Open **Setup** and allow the heart-rate sensor, background sensors
   (**All the time**), and notifications.
3. Open **Settings** and enter the WebSocket endpoint, token, and interval.
4. Tap **Test connection**, then **Save**.
5. Tap **Start**.

Enter only the token in the token field, not `Bearer `. The app adds the
`Authorization: Bearer <token>` header.

## Protocol

The app connects to the configured WebSocket URL.

During the handshake:

```text
Authorization: Bearer <token>
```

Every application message is:

- Binary, not text.
- Exactly one byte.
- An unsigned heart rate from 0 to 255.

There is no JSON, timestamp, acknowledgement, or other message type. The server
can use its receive time and can do whatever it wants with the reading. It does
not need to send anything back. An invalid token should be rejected, normally
with HTTP 401.

## Screenshots

![main](https://aether.menal.net/41a95a4919f65cdb.png)
![permission](https://aether.menal.net/fa93380f6d2184d2.png)
![settings](https://aether.menal.net/1baa8aba35621e4d.png)

## Notes

- A 1-second interval drains the battery quickly; increase it if you do not need
  live updates.
- `ws://` is unencrypted. Use `wss://` outside a trusted local network.
- After a reboot, the app tries to resume, but Android may require one tap on
  the notification.
- This is a personal tool, not a medical device.


