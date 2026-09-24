# Release signing

The release APK is signed with a keystore that is **not in this repository**.
`app/build.gradle.kts` reads four (or five) values from `keystore.properties` at
the project root, and when that file or the keystore it names is absent the
release build still runs — it just comes out unsigned, so a fresh checkout is
never blocked by missing credentials.

## The keystore

- **Type:** PKCS12. `keytool` writes PKCS12 on JDK 9+ whatever the file is
  called, and the existing copies are named `rizumu-release.jks` for history.
- **Entries:** one, alias `rizumu`.
- **Certificate:** `CN=Rizumu, O=Rizumu, C=IN`;
  SHA-256 `8ddf5e33d1d1dc134b8081e805e86a685aff1453c2106f0204329ed02018e5eb`.

Create one (only ever needs doing once):

```bash
keytool -genkey -v -keystore rizumu-release.jks \
    -keyalg RSA -keysize 2048 -validity 10000 -alias rizumu \
    -storetype PKCS12
```

Back up the file **and** the passwords. Losing them means no future build can
update an existing install; users would have to uninstall and lose their
library.

## `keystore.properties`

Gitignored; copy `keystore.properties.example`. All keys but `storeFile` are
secrets.

```properties
storeFile=/absolute/path/to/rizumu-release.jks
storePassword=…
keyAlias=rizumu
keyPassword=…
storeType=PKCS12
```

`storeType` is applied only when present, so an older JKS keystore that has
worked until now keeps working without editing this.

## CI (`build-prod-release`)

The keystore travels as a base64 secret, `KEYSTORE_BASE64`. Generate it from the
file and nothing else:

```bash
base64 -w0 /path/to/rizumu-release.jks
```

Set that as the repository secret, plus `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
`KEY_PASSWORD`. Keep exactly one secret holding the keystore — a stale
second name is how an empty decode sneaks in.

Before trusting it, prove the round trip:

```bash
base64 -w0 /path/to/rizumu-release.jks | base64 -d | sha256sum
sha256sum /path/to/rizumu-release.jks        # the two must match
```

The workflow decodes the secret, then checks the file is non-empty, starts with
the PKCS12 DER tag (`3082`), and can be listed by `keytool` with the store
password — all before Gradle runs. Every secret is passed through `env:` rather
than interpolated into the shell, so a password containing `$`, a backtick or a
backslash cannot be mangled or executed.

## Reading a signing failure

- `Failed to read key from store … Tag number over 30 is not supported` — the
  bytes are not a keystore. It is a secret problem, not a password problem; the
  workflow's `keytool`/`xxd` checks now catch it first with a clearer message.
- `Keystore was tampered with, or password was incorrect` — the file is a
  keystore but `storePassword` is wrong.
- `Keystore file not found` — `storeFile` does not resolve; in CI it is written
  as an absolute path under `$GITHUB_WORKSPACE`.

## Verifying a build

```bash
apksigner verify --print-certs app/build/outputs/apk/prod/release/app-prod-arm64-v8a-release.apk
```

The signer should read `CN=Rizumu` with the SHA-256 above.
