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
java -jar target/netty-sidecar-proxy-1.0.0.jar
```

The proxy always uses TPROXY mode: it enables Netty's Linux epoll transparent socket option and connects each intercepted request to that TCP connection's original destination. It has no fixed-backend mode. TPROXY rules, policy routing, and pod capabilities are deployment responsibilities and are not configured here. The shaded build includes Netty native epoll runtimes for Linux x86_64 and ARM64.

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

The Netty sidecar beside the API gateway creates one chain identity and absolute Unix epoch deadline for a new outbound request when those headers are absent. It preserves existing values when a request already belongs to a chain, sends the headers to the original destination, and echoes the chain metadata on its response:

| Header | Meaning |
| --- | --- |
| `deadlinevalue` | Absolute deadline in epoch milliseconds, shared by every hop |
| `deadlineExceded` | `true` when the deadline has elapsed |
| `cancellation_Triggered` | `true` when cancellation has been signalled |
| `Request_id` | UUID identifying the request chain |

Configure `DEFAULT_DEADLINE_MILLIS` on the gateway sidecar (default `5000`). The proxy uses it only when a request reaches it without a deadline. `PROXY_DETECT_INBOUND_DEADLINE` controls deadline detection on a proxy instance (default `true`); set it to `false` on an instance that handles outbound hops so it forwards the inherited status without independently declaring an inbound deadline expired.

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
