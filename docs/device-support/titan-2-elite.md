# Unihertz Titan 2 Elite

**Support:** Official

The Titan 2 Elite is a primary Plektra test device. Its rounded lower display corners require calibrated insets for the status bar, variation row, Slim mode, and software keyboard.

Release checks cover the physical modifier and SYM keys, rounded-corner clipping, status indicators, touch targets, software keyboard geometry, and theme backgrounds. Trackpad behaviour remains under active refinement and is not a recommended-settings focus.

The *Keyboard backlight* device-control command opens the system keyboard backlight settings and can be assigned to a Nav Mode key or an assigned key. It is offered only where that system screen exists.

*Backlight follows display* (Hardware keyboard settings, Keyboard backlight) sets the vendor keyboard backlight timeout to `-1`. The vendor light controller then keeps the keyboard light on until the display turns off and lights it again when the display turns on; Plektra runs nothing in the background. Reading and changing the value requires Shizuku. Plektra stores the previous timeout on the device before the first change, outside Android backups, and restores exactly that value when the option is turned off. The option is offered only on firmware whose vendor service was verified (currently V02.00.02, `BP2A.250605.031.A3`); after a firmware update Plektra no longer changes the value and points to the system backlight settings instead.
