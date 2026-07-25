# Choose Poker’s real build and deployment boundary

Type: prototype
Status: resolved

## Question

On the API 32 RG-glasses, what application-module structure, minimum/target
SDK, shared-code boundary, ABI packaging, signing, installation, and
suspend/resume assumptions must the real Poker target adopt using public
Android APIs only?

The answer must decide whether the existing pure Kotlin modules can be
consumed directly, identify how the API 33 mock target coexists with the real
target, and encode only behavior proven on the physical device.

## Answer

Poker is an ordinary Android application on the observed Android 12/API 32
glasses. It uses `minSdk = 28`, stable compile/target SDKs, public Android APIs,
and the existing pure Kotlin protocol/domain modules. It needs no vendor AAR,
authorization file, companion service, or proprietary application boundary.

The Android-only hotspot prototype built, installed, and ran as an ordinary
signed APK. Its listener, background lifecycle, sleep/wake, app restart, and
five-minute Fold6 locked-screen behavior are recorded on
`prototype/android-hotspot-transport` at commit `9d36ed1`. ADB remains an
installation and diagnostic tool only; it is not an application transport.
Production lifecycle and power-policy acceptance remains part of M5.

## Comments
