# Google integration setup (G1)

This is the server-side foundation only. OAuth endpoints and the iPhone Connect screen are G2. Do not add Google credentials to the iOS app or commit them to Git.

## Google Cloud project

1. Create or select a Google Cloud project for this TinyMe server.
2. Enable **Gmail API** and **Google Calendar API** in the project.
3. Set **Audience** to **External** and add your Google account as a test user while developing. For personal use, click **Publish app**, then choose **In production**. Google may show “Google hasn’t verified this app”; for your own account, click **Advanced** → **Go to [app name] (unsafe)** to continue. An unverified app using sensitive or restricted scopes is limited to about 100 users. If you leave the app in **Testing**, authorizations expire after seven days, so you must reconnect weekly. Public distribution with these scopes requires Google verification.
4. Create an OAuth client with application type **Web application**.
5. Add the exact callback URL as an authorized redirect URI:

   ```text
   https://<public-hostname>/integrations/google/callback
   ```

   Google requires HTTPS for non-localhost redirect URIs, and the URL must exactly match the server configuration. A Tailscale hostname is suitable only if it meets Google's redirect URI validation rules and can be reached by the phone's browser.
6. Keep the client ID and client secret in the server's `.env`, never in the app or Git:

   ```dotenv
   GOOGLE_CLIENT_ID=...
   GOOGLE_CLIENT_SECRET=...
   GOOGLE_REDIRECT_URI=https://<public-hostname>/integrations/google/callback
   ```

The OAuth server flow uses offline access so TinyMe can refresh access tokens for scheduled sync after the user leaves the browser. The later OAuth step requests only the scopes listed in the architecture guide and stores only the scopes Google actually grants.

## Encryption key

`TINYME_ENCRYPTION_KEY` is a base64-encoded Tink JSON AEAD keyset. Install Tinkey with `brew tap tink-crypto/tink-tinkey https://github.com/tink-crypto/tink-tinkey && brew install tinkey`, then generate the value with:

```sh
tinkey create-keyset --key-template AES256_GCM --out-format json --out /dev/stdout | base64 | tr -d '\n'
```

Copy the output into `.env` without committing it. Back up the key separately from Postgres. Keep the same keyset after restarts; losing it makes stored Google refresh tokens unreadable and requires reconnecting Google. For rotation, add a new key to the Tink keyset and promote it to primary while retaining the old key so existing ciphertext can still be decrypted.

For local development, add the value to the ignored root `.env`. For deployment, inject it as a secret environment variable. The application loads the keyset at startup when present; leaving it unset does not affect current TinyMe features, but Google cannot be connected until it is configured.

The server uses the AEAD associated data `google_account:1` when encrypting and decrypting the refresh token. The ciphertext is stored in `google_account.refresh_token_enc`. Both `google_account` and short-lived `oauth_states` are cleared by Delete all and excluded from data exports.

## Current database change

Flyway migration `V1.13__google_oauth_storage.sql` creates the single-owner `google_account` row and temporary OAuth state table. It does not initiate OAuth or make Google API calls.

Official setup references: [Google OAuth web-server flow](https://developers.google.com/identity/protocols/oauth2/web-server), [Google Workspace API setup](https://developers.google.com/workspace/guides/enable-apis), and [Tink AEAD encryption](https://developers.google.com/tink/encrypt-data).
