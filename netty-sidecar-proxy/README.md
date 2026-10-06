# Netty Sidecar HTTP Proxy

A small educational HTTP sidecar proxy built with **Java 17, Maven, and Netty 4.1.115.Final**.

## Architecture

```text
Client / Service A
       |
       | HTTP request
       v
+-------------------------+
|  Netty TPROXY Sidecar   |
|       :8080             |
|                         |
|  HttpServerCodec        |
|  HttpObjectAggregator   |
|  ProxyHandler           |
+------------+------------+
             |
             | HTTP request
             | original destination + propagation headers
             v
+-------------------------+
|       Service B         |
|       :9000             |
+------------+------------+
             |
             | HTTP response
             v
       Proxy response
       + X-Proxy-Processed
             |
             v
           Client
```

The application continues to call Service B using its normal destination. In Kubernetes/Linux, TPROXY can redirect that traffic to the sidecar, which reads the original destination and forwards the request there.

## Current implementation

- Java 17
- Maven
- Netty 4.1.115.Final
- HTTP/1.1
- Listens on `0.0.0.0:8080`
- Reads the original destination from each intercepted TPROXY TCP connection
- Requires Linux Netty epoll and `IP_TRANSPARENT`
- Adds `X-My-Proxy: Netty-Sidecar-Proxy`
- Adds `X-Proxy-Processed: true` to backend responses
- Configurable through environment variables
- 5-second destination connection timeout
- 10 MB aggregated HTTP message limit

## Important learning point: TCP streams

TCP does not send "headers first" as a special TCP concept.

The HTTP bytes are written into a TCP byte stream. TCP divides that stream into segments and numbers them with sequence numbers. The receiver reorders segments when necessary and exposes an ordered byte stream to the HTTP layer.

Conceptually:

```text
HTTP bytes:
[headers][blank line][body]

TCP:
segment 1 -> bytes N..M
segment 2 -> bytes M+1..P
segment 3 -> bytes P+1..Q

Receiver:
TCP reassembles the ordered byte stream
                |
                v
HTTP decoder sees:
[headers][blank line][body]
```

The HTTP decoder (`HttpServerCodec`) is therefore working on an ordered stream of bytes, not on individual TCP packets.

## Build and run

The Dockerfile uses two stages: Maven builds the proxy JAR in the first stage, then the second stage copies the JAR into a Java 17 runtime image. You do not need to build the JAR on your computer first.

From this directory, build the image with Docker Compose:

```bash
docker compose build proxy
```

To start the Compose service after the image is built:

```bash
docker compose up proxy
```

The proxy still needs Linux TPROXY setup and the required transparent-socket permission at runtime.

You can also build and run the JAR directly with Maven:

```bash
mvn clean package
```

Run on Linux with Netty native epoll available and the required transparent socket permission:

```bash
java -jar target/netty-sidecar-proxy-1.0.0.jar
```

## Configuration

Linux:

```bash
export PROXY_LISTEN_HOST=0.0.0.0
export PROXY_LISTEN_PORT=8080
export DEFAULT_DEADLINE_MILLIS=5000
export PROXY_DETECT_INBOUND_DEADLINE=true
export PROXY_ROLE=service
java -jar target/netty-sidecar-proxy-1.0.0.jar
```

Inbound requests use Linux TPROXY: the proxy enables Netty's epoll transparent socket option and forwards to the original destination preserved on the accepted socket. Outbound HTTP requests to the service ports are redirected by the pod's `OUTPUT` NAT rules; because that redirection changes the accepted socket's local address, the proxy resolves the outbound destination from the HTTP `Host` header (or an absolute `http://` request URI). The outbound rules exclude UID `10001`, which is the proxy process, so its backend connections do not loop back through itself.

The Kubernetes manifests configure inbound TPROXY rules and policy routing in an init container, plus outbound redirection for TCP ports `8081` through `8084`. This proof of concept handles HTTP/1.1 only; it does not intercept HTTPS/TLS or arbitrary TCP protocols. The shaded build includes Netty native epoll runtimes for Linux x86_64 and ARM64.

For the Minikube Docker driver, build and load the image referenced by the manifests before applying them:

```bash
docker build -f netty-sidecar-proxy/docker/Dockerfile \
  -t 2002rusira/netty-sidecar-proxy-rpe-implementation:v2 \
  netty-sidecar-proxy
minikube image load 2002rusira/netty-sidecar-proxy-rpe-implementation:v2
```

Then apply the API gateway and backend service manifests. Their init containers install the pod-local routing rules before the application and proxy start.

## Project structure

```text
netty-sidecar-proxy/
├── pom.xml
├── README.md
└── src/
    ├── main/
    │   └── java/
    │       └── com/example/proxy/
    │           ├── ProxyConfig.java
    │           ├── ProxyServer.java
    │           ├── ProxyInitializer.java
    │           ├── ProxyHandler.java
    │           ├── BackendInitializer.java
    │           └── BackendResponseHandler.java
    └── test/
        └── java/
            └── com/example/proxy/
                └── ProxyConfigTest.java
```

## Deadline and cancellation propagation (proof of concept)

For the simple-English end-to-end flow, example TPROXY rule shape, pod permission notes, and rollout checklist, see [DEADLINE_TPROXY_GUIDE.md](DEADLINE_TPROXY_GUIDE.md).

The Netty sidecar beside the API gateway creates one chain identity and absolute Unix epoch deadline for a new outbound request when those headers are absent. It preserves existing values when a request already belongs to a chain, sends the headers to the original destination, and echoes the chain metadata on its response:

| Header | Meaning |
| --- | --- |
| `deadlinevalue` | Absolute deadline in epoch milliseconds, shared by every hop |
| `deadlineExceded` | `true` when the deadline has elapsed |
| `cancellation_Triggered` | `true` when cancellation has been signalled |
| `Request_id` | UUID identifying the request chain |

Configure `DEFAULT_DEADLINE_MILLIS` on the gateway sidecar (default `5000`). The proxy uses it only when a request reaches it without a deadline. `PROXY_DETECT_INBOUND_DEADLINE` controls deadline detection on a proxy instance (default `true`); set it to `false` on an instance that handles outbound hops so it forwards the inherited status without independently declaring an inbound deadline expired.

Set `PROXY_ROLE=gateway` only on the API gateway sidecar. That is the only role allowed to create a missing `Request_id` or `deadlinevalue`. Service sidecars default to `PROXY_ROLE=service`; they require the inbound chain headers or a matching saved context for an outbound request containing only `Request_id`. A service sidecar returns HTTP 400 instead of silently starting a new deadline if the chain context is missing.

The order service captures only `Request_id` for the lifetime of its synchronous inbound request and adds only that header to inventory, payment, and notification calls. Its sidecar looks up the saved inbound context by ID and fills in `deadlinevalue`, `deadlineExceded`, and `cancellation_Triggered`. A new chain with no ID gets its UUID and deadline from the gateway sidecar. The sidecar retains the inbound chain context until the owning inbound request completes; completing an outbound hop does not evict it. When the deadline arrives, the sidecar updates the stored flags so later outbound hops carry the cancellation signal.

Every service hop still needs to be routed through a proxy instance for proxy-side behavior to run. The proxy observes deadline expiry and propagates the resulting flags; it does not interrupt backend application work or send a separate asynchronous cancellation message to an already-running downstream request. Cancellation here is a propagated signal for application code to observe, consistent with this prototype's scope.

## What this version does not yet implement

This is a learning/reference implementation rather than a production transparent proxy.

The next engineering steps are:

1. Persistent backend connections / connection pooling.
2. Correct handling of HTTP keep-alive and connection lifecycle.
3. Read/write timeouts.
4. Backpressure and bounded buffering.
5. Streaming bodies instead of aggregating every request/response.
6. HTTPS/TLS interception where appropriate.
7. Metrics and structured logging.
8. Health/readiness endpoints.
9. Kubernetes sidecar manifests.
10. Linux iptables/TPROXY original-destination handling.
11. Loop prevention when traffic is transparently redirected.
12. Graceful shutdown.
13. Separate asynchronous cancellation notifications to already-running downstream calls.
14. Integration tests with a real backend.

## Security note

Do not use transparent interception or TLS interception against systems you do not own or administer. In a real deployment, traffic-redirection rules and certificate handling must be designed for the environment and its authorization model.
