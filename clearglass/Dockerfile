# --- Chef base ---
FROM rust:bookworm AS chef
RUN cargo install cargo-chef
RUN apt-get update && apt-get install -y cmake && rm -rf /var/lib/apt/lists/*
WORKDIR /build

# --- Planner: analyze dependencies ---
FROM chef AS planner
COPY . .
RUN cargo chef prepare --recipe-path recipe.json

# --- Builder: build deps (cached) then app ---
FROM chef AS builder
COPY --from=planner /build/recipe.json recipe.json
RUN cargo chef cook --release --recipe-path recipe.json
COPY . .
RUN cargo build --release --bin clearglass

# --- Runtime: minimal image ---
FROM gcr.io/distroless/cc-debian12
COPY --from=builder /build/target/release/clearglass /
ENV RUST_LOG=info
EXPOSE 8080
CMD ["/clearglass"]
