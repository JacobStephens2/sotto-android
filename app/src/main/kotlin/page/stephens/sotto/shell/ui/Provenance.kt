package page.stephens.sotto.shell.ui

// The About screen's provenance statement (constitution boundary 6), kept out
// of the Activity so ProvenanceTest can hold each flavor to its own claim.

object Provenance {
    fun about(networked: Boolean): String =
        "Sotto narrates documents on this device. The voice is " +
            "synthetic, produced by Kokoro-82M - an openly licensed (Apache-2.0) " +
            "model trained on documented public-domain and permissively licensed " +
            "audio. " + if (!networked) {
                "Nothing you share with Sotto leaves the phone: this build " +
                    "holds no INTERNET permission, which anyone can verify with " +
                    "`aapt dump permissions` on the APK."
            } else {
                "This build can reach the network, only for an account you " +
                    "connect: it syncs your library with the Sotto server you name, " +
                    "and if you switch on server narration for an oration, it sends " +
                    "that text to your server, which narrates it with OpenAI voices. " +
                    "Nothing else leaves the phone."
            }
}
