# Totem relay on Cloudflare

The same relay as `../relay` (Rust), as a Cloudflare Worker with one Durable Object. It's always on,
uses `wss://`, and works on the free plan: a handful of requests a day is far below the free
limits. Use it if you want codes when the phone isn't on the same Wi-Fi as the PC.

Cloudflare sees what any relay sees: device ids, timing and encrypted blobs. Codes are encrypted
end to end between phone and PC (Noise), so not even Cloudflare can read them.

## Deploy (about 5 minutes)

You need a free Cloudflare account. No domain is needed: you get `totem-relay.<you>.workers.dev`.

```sh
cd relay-worker
npm install
npx wrangler login                 # opens the browser once
npx wrangler deploy                # prints https://totem-relay.<you>.workers.dev
# Lets the relay wake your phone with a push (same Firebase key the desktop app uses):
npx wrangler secret put FCM_SERVICE_ACCOUNT < ~/.secrets/<project>-firebase-adminsdk-<id>.json
```

Check it: open `https://totem-relay.<you>.workers.dev/health`. It should say `ok`.

Then switch over: on the PC, tray icon → **Pair with phone…**, enter
`wss://totem-relay.<you>.workers.dev/v1/ws` → **Show code**, and scan it on the phone. The desktop app
stops running its own relay from the next start. To go back, pair again with the `ws://…:8080`
address it suggests.

## Develop and test

```sh
npm run dev                                                    # local Worker on :8787
TOTEM_TEST_RELAY_URL=ws://127.0.0.1:8787/v1/ws cargo test -p totem-relay -p totem-client
npm run check                                                  # TypeScript
```

The Rust relay tests and the full pairing / code test run unchanged against it. For pushes
locally, put `FCM_SERVICE_ACCOUNT='<the JSON on one line>'` in `.dev.vars` (gitignored), and
delete it afterwards.
