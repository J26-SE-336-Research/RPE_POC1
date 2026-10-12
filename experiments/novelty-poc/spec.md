
# 1. Purpose

Define a common input model for comparing RRPE context encodings.
Every candidate encoding must represent the same values.

## 2. Proposed HTTP Header

Header name: X-RRPE-Context

The header value's encoding has not yet been selected.

Candidates:

- Delimited key-value
- HTTP Structured Fields
- Base64-encoded CBOR

## 3. Experimental Fields

| Field | Logical type | Meaning | Allowed values |

| v | Integer | Protocol version | 1 for this experiment |
| rid | String | Request-chain identifier | 32 lowercase hexadecimal characters; not all zeros |
| dl | Integer | Absolute deadline in Unix milliseconds | 0 to 9007199254740991 |
| rb | Integer | Remaining additional retry attempts | 0 to 65535 |
| bp | Integer | Backpressure indicator | 0 = none reported; 1 = reported |
| cs | Boolean | Cancellation requested | false or true |

These bounds and the binary backpressure model are experimental choices.

## 4. Validation Rules

- All six fields are required.
- Duplicate fields are invalid.
- Unsupported versions are invalid in this experiment.
- Unknown fields are invalid in this experiment.
- Values outside the declared types or ranges are invalid.
- Invalid input produces a validation error.

An expired deadline is a valid context value.
Expiry is evaluated separately from format validation.

## 5. Transition Rules for Later Tests

For a parent-to-child context transition:

- v must remain unchanged.
- rid must remain unchanged.
- dl must not increase.
- rb must not increase.
- cs must not change from true to false.

Backpressure update and response-merge rules remain undecided.

## 6. Initial Test Context

v: 1
rid: 4bf92f3577b34da6a3ce929d0e0e4736
dl: 1791655200000
rb: 2
bp: 0
cs: false

This deadline is a fixed codec test value.

## 7. Success Criterion

Decoding an encoded valid context must recover every original
field value exactly.

The delimited candidate uses semicolon-separated key=value pairs.
The cs field encodes false as 0 and true as 1.
