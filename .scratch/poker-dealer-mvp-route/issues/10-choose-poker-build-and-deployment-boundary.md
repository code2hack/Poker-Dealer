# Choose Poker’s real build and deployment boundary

Type: prototype
Status: open
Blocked by: 02

## Question

Using the official `glassdemo` project on the API 32 RG-glasses, what application-module structure, minimum/target SDK, shared-code boundary, ABI packaging, signing, installation, authorization, and suspend/resume assumptions must the real Poker target adopt?

The answer must decide whether the existing pure Kotlin modules can be consumed directly, identify how the API 33 mock target coexists with the real target, and encode only behavior proven by the vendor sample rather than assuming an ordinary Android application environment.

## Comments
