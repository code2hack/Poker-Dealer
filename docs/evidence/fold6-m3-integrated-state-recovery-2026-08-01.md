# Fold6 M3 integrated state-recovery proof — 2026-08-01

## Scope

This follow-up verifies issue #30 after issues #22 and #28 were integrated into `main`. It repeats
real Fold6 process-death and same-phone reboot checks with the final integrated Dealer build. ADB
was used only to install the APK, deliver `SIGKILL`, reboot Android, inspect privacy-safe state, and
explicitly relaunch Dealer.

Private addresses, ports, credentials, host pins, thread identifiers, content, and app-server
endpoints are intentionally omitted. DGX Spark was the only workstation backend used; u4090 and CI
artifact production were excluded.

## Device and build

- Samsung SM-F956N (Fold6), Android 16, build `BP4A.251205.006`.
- Merged `main` commit: `0a4d65f333005603713e6e08f980c40c630e266a`.
- Dealer `0.1.0-m1` debug APK SHA-256:
  `e89d682a1342579c9f40f0497c3c2e8921a64c88da38b2ff1754a9e9e967c739`.
- The APK was installed in place with `adb install -r`, preserving Dealer-private state.
- The native AAR came from the committed persistent amd64 Docker builder running locally on ARM64
  Spark. No remote artifact fallback was used.

Before destructive testing, Dealer had automatically connected to Spark through
`SSH_EMBEDDED_TSNET`. One Spark thread was attached, one draft existed, and no pending outbound
action or server request was present. The projection showed no Dealer control claim.

## Integrated process-death proof

Dealer's application process received raw `SIGKILL`; package force-stop was not used. Five seconds
later the process and foreground service were absent, no Dealer notification was active, and the
package stopped flag remained false. Dealer was then explicitly cold-launched.

After the bounded reconnect interval:

- the embedded tailnet was connected;
- the enabled Spark session reconnected without a Connect tap;
- the existing attachment resumed silently with inherited settings;
- the attachment and draft counts remained one;
- the draft SHA-256 matched the pre-kill value;
- pending outbound actions and all three pending request families remained empty;
- recovered intended control was `NONE`, and the UI offered **Take control**; and
- the only active Dealer notification was foreground-service notification ID 4090.

## Integrated reboot proof

ADB rebooted the real Fold6. The post-reboot kernel boot identifier differed from the pre-reboot
identifier. Before Dealer was launched:

- Dealer had no process or service;
- Dealer had no active notification;
- the package stopped flag remained false;
- the attachment database still contained one attachment, one draft, and zero pending actions;
- the draft SHA-256 was unchanged; and
- the private no-backup projection, request, and retained-card files remained present.

Android changed the wireless-debugging endpoint during reboot. After the user re-enabled access,
the paired local-Wi-Fi ADB connection was used to complete the time-sensitive observation. Dealer
was then explicitly cold-launched; no boot receiver or automatic phone-boot start was involved.

After the bounded reconnect interval, the embedded tailnet and Spark session connected without a
Start or Connect tap. The attachment, exact draft hash, zero pending-action state, and zero pending
request-family state were unchanged. The recovered projection contained one attached thread with
intended control `NONE`. The UI exposed **Detach** and **Take control**. Only foreground-service
notification ID 4090 was active; recovery synthesized no attention or ready notification.

## Automated and live gates

The final Spark gate for this APK passed:

```text
./gradlew test lint :apps:dealer:verifyEmbeddedTailnetPackaging \
  -Dorg.gradle.parallel=false \
  -Pandroid.aapt2FromMavenOverride=/home/code2hack/Android/Sdk/build-tools/36.1.0/aapt2

BUILD SUCCESSFUL in 32s
97 actionable tasks: 27 executed, 70 up-to-date
```

The integrated spec and repository-standards reviews found no remaining merge blocker. Issues #22
and #28 were closed after their merged behavior and evidence were verified.

## Result and limits

This completes the integrated real-device process-death and reboot acceptance for issue #30. The
live run covered one Spark attachment and one draft with no in-flight action or server request.
Fail-closed `UNKNOWN` restoration and no-replay behavior for interrupted command, file,
structured-user-input, and outbound turn actions remain proven by automated tests rather than a
live in-flight destructive run. This is not a long-duration Doze, OEM-kill, or battery-life claim.
