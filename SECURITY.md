# Security

droidprobe is a debugging tool. It is meant for debug builds on development devices and emulators,
never for builds you ship to users.

## Security model

- **Debug builds only.** The SDK is added with `debugImplementation`, so release builds contain no
  `dev.droidprobe` classes and open no socket.
- **Reachable only through adb.** The app listens on an abstract Unix socket
  (`@<applicationId>.probe`). It opens no TCP port; the CLI reaches it with `adb forward`.
- **Peer check.** On every connection the app reads the peer's pid and uid and accepts it only if
  that peer holds `android.permission.DUMP`. adbd (shell or root) holds it; other apps on the
  device do not.
- **Local dashboard.** `probe ui` binds to 127.0.0.1 and is read-only unless started with
  `--allow-actions`.
- **Masked headers.** Recorded network traffic masks credential headers (`Authorization`,
  `Cookie`, `Set-Cookie`, names containing `token`, and app-configured names). Bodies are recorded
  as is, so treat recorded traffic as sensitive.

Anyone with adb access to the device can read and change what the probe exposes. That is the
intended trust boundary.

## Reporting a vulnerability

Please report vulnerabilities through GitHub's private vulnerability reporting
("Report a vulnerability" under this repository's Security tab). Do not open a public issue.
