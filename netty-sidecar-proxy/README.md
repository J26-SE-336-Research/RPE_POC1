# Netty Sidecar HTTP Proxy

A small educational HTTP sidecar proxy built with **Java 17, Maven, and Netty 4.1.115.Final**.

## Architecture

```text
Client / Service A
       |
       | HTTP request
       v
+-------------------------+
|  Netty Sidecar Proxy    |
|       :8080             |
|                         |
|  HttpServerCodec        |
|  HttpObjectAggregator   |
|  ProxyHandler           |
+------------+------------+
             |
             | HTTP request
             | + X-My-Proxy header
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

The application can continue to think it is communicating with Service B. In a Kubernetes/Linux sidecar deployment, traffic can later be redirected to the proxy with iptables/TPROXY while the application itself remains configured for Service B.

## Current implementation

- Java 17
- Maven
- Netty 4.1.115.Final
- HTTP/1.1
- Listens on `0.0.0.0:8080`
- Default backend: `localhost:9000`
- Adds `X-My-Proxy: Netty-Sidecar-Proxy`
- Adds `X-Proxy-Processed: true` to backend responses
- Configurable through environment variables
- 5-second backend connection timeout
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

## Run locally

### 1. Start a backend service

For a quick test:

```bash
python -m http.server 9000
```

Run that from a directory containing a file such as `index.html`.

### 2. Build the proxy

```bash
mvn clean package
```

### 3. Run the proxy

```bash
java -jar target/netty-sidecar-proxy-1.0.0.jar
```

Default configuration:

```text
Proxy:   http://localhost:8080
Backend: http://localhost:9000
```

### 4. Send a request

```bash
curl -v http://localhost:8080/
```

The request goes:

```text
curl -> proxy:8080 -> backend:9000 -> proxy -> curl
```

## Configuration

Windows CMD:

```cmd
set PROXY_LISTEN_HOST=0.0.0.0
set PROXY_LISTEN_PORT=8080
set BACKEND_HOST=localhost
set BACKEND_PORT=9000
java -jar target\netty-sidecar-proxy-1.0.0.jar
```

Linux/macOS:

```bash
export PROXY_LISTEN_HOST=0.0.0.0
export PROXY_LISTEN_PORT=8080
export BACKEND_HOST=localhost
export BACKEND_PORT=9000

java -jar target/netty-sidecar-proxy-1.0.0.jar
```

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

The Netty sidecar beside the API gateway creates one chain identity and absolute Unix epoch deadline for a new outbound request when those headers are absent. It preserves existing values when a request already belongs to a chain, sends the headers to the backend, and echoes the chain metadata on its response:

| Header | Meaning |
| --- | --- |
| `deadlinevalue` | Absolute deadline in epoch milliseconds, shared by every hop |
| `deadlineExceded` | `true` when the deadline has elapsed |
| `cancellation_Triggered` | `true` when cancellation has been signalled |
| `Request_id` | UUID identifying the request chain |

Configure `DEFAULT_DEADLINE_MILLIS` on the gateway sidecar (default `5000`). The proxy uses it only when a request reaches it without a deadline. `PROXY_DETECT_INBOUND_DEADLINE` controls deadline detection on a proxy instance (default `true`); set it to `false` on an instance that handles outbound hops so it forwards the inherited status without independently declaring an inbound deadline expired.

The order service captures only `Request_id` for the lifetime of its synchronous inbound request and adds only that header to inventory, payment, and notification calls. Its sidecar looks up the saved inbound context by ID and fills in `deadlinevalue`, `deadlineExceded`, and `cancellation_Triggered`. A new chain with no ID gets its UUID and deadline from the gateway sidecar. The sidecar retains the inbound chain context until the owning inbound request completes; completing an outbound hop does not evict it. When the deadline arrives, the sidecar updates the stored flags so later outbound hops carry the cancellation signal.

This repository's proxy still has one configured backend per process and is not yet installed into `docker-compose.yml` as a transparent sidecar. Every service hop must be routed through a correctly configured proxy instance for proxy-side behavior to run. The proxy observes an elapsed deadline when handling a request and reflects the resulting flags in the response; it does not interrupt backend application work or send a separate asynchronous cancellation message to an already-running downstream request. Cancellation here is a propagated signal for application code to observe, consistent with this prototype's scope.

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
