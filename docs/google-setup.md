# Google integration setup

Google OAuth, Gmail tools and mail suggestions, Calendar tools and CalendarSync are implemented through G9. Do not add Google credentials to the iOS app or commit them to Git.

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

The OAuth server flow uses offline access so Gmail and Calendar features can refresh access tokens after the user leaves the browser. TinyMe requests `gmail.readonly`, `gmail.compose`, `calendar.events`, `calendar.calendarlist.readonly`, `calendar.events.freebusy`, and `calendar.app.created`, plus `openid` and `email`; it stores only the scopes Google actually grants. Calendar list and free/busy access use separate read-only scopes required by Google's Calendar API methods. If TinyMe was connected before these Calendar scopes were added, reconnect Google and approve them.

## Encryption key

`TINYME_ENCRYPTION_KEY` is a base64-encoded Tink JSON AEAD keyset. Install Tinkey with `brew tap tink-crypto/tink-tinkey https://github.com/tink-crypto/tink-tinkey && brew install tinkey`. Tinkey requires the output file not to exist, so generate to a temporary directory, then base64-encode it:

```sh
TINYME_KEYSET_TMP="$(mktemp -d)"
tinkey create-keyset --key-template AES256_GCM --out-format json --out "$TINYME_KEYSET_TMP/keyset.json" && \
  base64 < "$TINYME_KEYSET_TMP/keyset.json" | tr -d '\n' && printf '\n'
rm "$TINYME_KEYSET_TMP/keyset.json"
rmdir "$TINYME_KEYSET_TMP"
```

Copy the output into `.env` without committing it. Back up the key separately from Postgres. Keep the same keyset after restarts; losing it makes stored Google refresh tokens unreadable and requires reconnecting Google. For rotation, add a new key to the Tink keyset and promote it to primary while retaining the old key so existing ciphertext can still be decrypted.

For local development, add the value to the ignored root `.env`. For deployment, inject it as a secret environment variable. The application loads the keyset at startup when present; leaving it unset does not affect current TinyMe features, but Google cannot be connected until it is configured.

The server uses the AEAD associated data `google_account:1` when encrypting and decrypting the refresh token. The ciphertext is stored in `google_account.refresh_token_enc`. Both `google_account` and short-lived `oauth_states` are cleared by Delete all and excluded from data exports.

After configuring the client and encryption key, sign in to TinyMe on the iPhone, open **Settings → Google → Connect Google**, and complete Google's sign-in in the secure browser sheet. TinyMe returns to the app and shows the email and the Gmail/Calendar scopes actually granted. Disconnect asks for the TinyMe password, revokes the Google grant when Google is reachable, and removes the server's saved connection.

## Connection and sync

Flyway migrations add encrypted Google-account storage, Gmail confirmations, calendar sync cursors, and mail-suggestion data. The iPhone completes sign-in through Apple's secure browser session and returns to TinyMe using the `tinyme://google-connected` URL scheme. CalendarSync mirrors TinyMe plans to its own Google calendar and imports selected calendars as read-only plans in Upcoming.

## Mail suggestions (G9)

TinyMe scans recent Gmail messages every 15 minutes and when the app opens. On first connect it checks only the previous seven days; later runs use Gmail's history cursor. Sent, draft, spam, trash, chat, promotions, social mail and ordinary mailing lists are skipped. Gmail metadata is checked first; message bodies are fetched only for candidates. Defaults cap scanning at 30 messages per run and 200 per day. Scanner failures retry once, then the message is marked failed. Plan suggestions expire after their date passes. The isolated scanner agent can only propose upcoming events, tasks and reminders, card and bank payments, and the postings in LinkedIn job alerts (which are read even from the Social and Promotions tabs). Everything waits in the app's **G Scan** tab: accept a plan before it reaches Upcoming, and confirm a payment before it counts as spending (refunds count against it). Payments in another currency are converted to yours at that day's rate. Job postings get a status you set, or you delete them; a deleted job never comes back. A time with an email-provided IANA time zone is converted to your configured TinyMe zone while retaining the original time for display.

Scan run records are pruned after 30 days and per-message scan markers after 90 days. Accepted plans keep a Gmail message reference so **View email** can open the source message; email bodies are not stored in TinyMe.

The optional server settings are `TINYME_MAILSCAN_INTERVAL` (for example `15m`), `TINYME_MAILSCAN_MAX_PER_RUN` (`30`) and `TINYME_MAILSCAN_MAX_PER_DAY` (`200`). Email bodies go to the configured scanner agent for extraction but are not stored in TinyMe's database or tool-call logs. TinyMe stores only proposed plan fields and a short evidence quote for review.

Official setup references: [Google OAuth web-server flow](https://developers.google.com/identity/protocols/oauth2/web-server), [Google Workspace API setup](https://developers.google.com/workspace/guides/enable-apis), and [Tink AEAD encryption](https://developers.google.com/tink/encrypt-data).
