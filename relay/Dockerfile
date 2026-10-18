# Totem relay (Rust) for a server of your own. Build from the repo root:
#   docker build -f relay/Dockerfile -t totem-relay .
#   docker run -p 8080:8080 -v totem-relay:/data \
#     -v ~/.secrets/<firebase-adminsdk>.json:/run/secrets/fcm.json:ro -e TOTEM_FCM_KEY=/run/secrets/fcm.json \
#     totem-relay
# Put it behind a TLS proxy (Caddy, nginx, Cloudflare Tunnel): phones and PCs only accept
# wss:// for addresses outside the home network.
FROM rust:1.89-slim-bookworm AS build
WORKDIR /src
COPY . .
RUN cargo build --release --locked -p totem-relay

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates && rm -rf /var/lib/apt/lists/* \
    && useradd --system --home /data totem && mkdir -p /data && chown totem /data
COPY --from=build /src/target/release/totem-relay /usr/local/bin/totem-relay
USER totem
VOLUME /data
EXPOSE 8080
ENTRYPOINT ["totem-relay", "--listen", "0.0.0.0:8080", "--store", "/data/relay-store.json"]
