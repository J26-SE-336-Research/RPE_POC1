
# Purpose

This protocol defines a shared resilience context carried in an HTTP header.
The context allows participating services to exchange information about request deadline,retry budgets, backpressure and cancellation.

This specification defines how that information is represented,validated and updated across service calls.

# Scope

- Context fields, their meanings and allowed values.
- Validation and context-transition rules.
- Deadline and expiry semantics.
- Retry-budget propagation and allocation.
- Cancellation signalling.
- Backpressure signalling.
- Fan-out behaviour.

## 3. Terminology and Component Responsibilities

### 3.1 Terminology

A participating service or proxy is one that understands and processes the RRPE context.

A request chain consists of an initial request and the downstream calls made to fulfil it.

A hop is one HTTP call from a caller to a receiving service.

The resilience context is the metadata carried in X-RRPE-Context.It contains the resilience information defined by this specification.

A context transition is a change to that metadata during processing, propagation, retry or fan-out.

### 3.2 Protocol Specification

The specification defines:

- The meaning and representation of each context field.
- Which context values and transitions are valid.
- How context is propagated across requests and responses.
- The required behaviour for missing or invalid context.
- Deadline, retry-budget, cancellation and backpressure semantics.

### 3.3 Reference Libraries

### 3.4 Interceptors and Proxies

Interceptors read, propagate and update the context according to the protocol rules.

### 3.5 Application Responsibilities

Application code identifies where cancellation can safely be honoured, especially during operations with side effects.
Receiving a cancellation signal does not mean an operation has stopped, and does not imply that completed changes were rolled back.
