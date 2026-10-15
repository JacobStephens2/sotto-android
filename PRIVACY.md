# Privacy

Sotto collects nothing and transmits nothing. It cannot: the `local` build
holds no INTERNET permission, which you can verify on any APK -

    aapt dump permissions sotto-local-<version>.apk

Everything Sotto makes - your documents, the narrated audio, the activity
log - stays in the app's private storage on your device. Export and deletion
are yours: exporting copies a file where you choose; deleting really deletes.

The `sync` build adds network access for an account you connect, and nothing
else. When - and only when - you connect one, it exchanges your library with
the Sotto server *you name*. Narration stays on-device unless you switch on
"Narrate on the server (OpenAI)" for an oration (offered only when your
account allows it): that oration's text then goes to your server, which sends
it to OpenAI to be narrated. No analytics, no crash reporting, in either
build; the `local` build has no third parties at all.
