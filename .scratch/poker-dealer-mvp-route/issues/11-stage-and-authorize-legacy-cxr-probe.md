# Stage and authorize the legacy CXR compatibility probe

Type: task
Status: open
Blocked by: 01

## Question

Stage the exact guide-matched CXR-M/CXR-S artifacts without changing glasses
firmware or system packages; sanitize the public phone sample; compile the
minimal glasses hello/ack app; and, once user-owned authorization is available,
build and install only the two application APKs needed to unblock the measured
transport probe in issue 03.

No credential, device identifier, `.lc` content, or vendor sample credential
may enter Git, logs, or committed build configuration.

## Comments

- The two guide-matched AARs and official `CXRMSamples_110.zip` are staged
  under the protected, Git-ignored `.local/rokid/cxr-maven/` tree with recorded
  checksums.
- The original sample archive contains vendor demo authorization material. It
  is quarantined as reference only and must not be installed or treated as the
  user's authorization. The extracted working copy has been sanitized to an
  explicit unconfigured placeholder.
- A minimal glasses-side CXR-S app has built successfully under
  `.local/rokid/cxr-probe/glasses/`; its signed debug APK has not been
  installed.
- A matching credential-free CXR-M custom-command compile probe has built
  successfully under `.local/rokid/cxr-probe/phone/`; it cannot connect and
  has not been installed.
- A protected, ignored handoff location is prepared at
  `.local/rokid/cxr-auth/`; credential contents must never be printed.
- The installed `com.rokid.cxrservice` is running and must remain untouched.
- Remaining external input: a verified Rokid developer-account client secret
  and a `.lc` authorization file downloaded after binding this glasses serial
  at `https://ar.rokid.com`.

## Answer
