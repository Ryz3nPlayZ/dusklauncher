# Two stages: build on the full Rust image, ship the binary on slim Debian.
FROM rust:1-slim-bookworm AS builder
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends pkg-config && rm -rf /var/lib/apt/lists/*
# dependency layer first so a source edit doesn't rebuild the world
COPY Cargo.toml Cargo.lock ./
RUN mkdir src && echo 'fn main(){}' > src/main.rs && echo '{}' > catalog.json && cargo build --release && rm -rf src catalog.json
COPY catalog.json ./
COPY src ./src
RUN touch src/main.rs && cargo build --release

FROM debian:bookworm-slim
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /data
ENV DUSK_DB=/data/dusk.db DUSK_BIND=0.0.0.0:8787
COPY --from=builder /app/target/release/dusk-server /usr/local/bin/dusk-server
EXPOSE 8787
CMD ["dusk-server"]
