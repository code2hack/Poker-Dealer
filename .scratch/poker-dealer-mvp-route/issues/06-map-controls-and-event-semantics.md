# Map Rokid controls and input-event semantics

Type: prototype
Status: open
Blocked by: 05, 10

## Question

For every physical device selected by **Choose the MVP physical input
devices**, which down/up, click, hold, repeat, swipe, touch, Android
`KeyEvent`, media-button, and standard HID events actually reach Poker or
Dealer, and which normalized command mapping safely supports reading, Morse
timing, and push-to-talk?

The answer is a measured public-Android event map and capability decision,
including explicit fallbacks for controls that lack key-up or reliable
duration. Undocumented vendor gesture APIs are outside this route.

## Comments
