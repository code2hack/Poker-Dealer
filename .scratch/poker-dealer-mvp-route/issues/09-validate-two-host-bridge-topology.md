# Validate the two-host bridge topology and failure model

Type: prototype
Status: open
Blocked by: 08

## Question

Given Fold6 as both Dealer device and local Termux host, plus the inventoried DGX Spark, does the specified loopback-local and pinned-WSS-over-Tailscale topology work without port conflicts, unsafe binding, duplicate input, or ambiguous host identity; and what concrete connection, pairing, backoff, and reconciliation parameters should implementation use?

The answer is a topology/security decision backed by disposable bridge clients and panes, not production bridge implementation.

## Comments
