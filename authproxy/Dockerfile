FROM golang:1.22-alpine AS build
WORKDIR /app
COPY go.mod ./
COPY *.go ./
RUN CGO_ENABLED=0 GOOS=linux go build -o auth-proxy .

FROM scratch
COPY --from=build /app/auth-proxy /auth-proxy
ENTRYPOINT ["/auth-proxy"]
