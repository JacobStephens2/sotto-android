# Privacy

Sotto collects nothing and transmits nothing. It cannot: the `local` build
holds no INTERNET permission, which you can verify on any APK -

    aapt dump permissions sotto-local-<version>.apk

Everything Sotto makes - your documents, the narrated audio, the activity
log - stays in the app's private storage on your device. Export and deletion
are yours: exporting copies a file where you choose; deleting really deletes.

The `sync` build adds exactly one capability: when - and only when - you
connect an account, it exchanges your library with the Sotto server *you
name*. Your text is never sent anywhere to be synthesized; narration is
on-device in both builds. No analytics, no crash reporting, no third parties,
in either build.
