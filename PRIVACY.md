# Data and permissions

Duo Launcher stores settings, Home layout, widget placement, and selected wallpaper locally. It has no account system, backend, advertising, analytics SDK, or automatic crash reporting.

## Data used on your device

- Installed app names, icons, launch activities, and eligible work-profile entries populate Home and All apps.
- Widget providers control their content, accounts, and network activity; Android hosts their widgets.
- Battery, Wi-Fi, cellular signal, and airplane-mode readings populate the Home status rail while visible. Signal display does not require location access.
- Selecting a photo creates a local preview. Apply commits it; cancel preserves the previous background. Android's picker grants access to chosen images only.
- Sunrise/sunset appearance stores coordinates you enter or explicitly request through approximate location. Times are calculated locally. There is no background location tracking, and Clear location removes the stored coordinates.

## Optional access

The shade-gesture accessibility service opens notifications or Quick Settings in response to your gesture. It cannot retrieve window contents or perform gesture injection and unsubscribes from accessibility events when connected. You can disable it in Android Accessibility settings and continue using the launcher.

Notification access, when you enable it, serves two features and nothing else. App icon badges read only the app, the profile, the notification channel's badge setting, and the notification count. The Now Playing widget reads the title, artist, and artwork of the active media session, and its controls play, pause, or skip that session. Notification text is never read or retained, neither feature writes to storage or to a backup, and both stop as soon as you turn notification access off in Android settings.

The Date and Calendar widget shows today's date without any permission. Granting calendar access adds your next three events, read from the system calendar each time the widget refreshes and only while the launcher is on screen. Duo reads an event's title, start and end time, and its calendar's display colour; it does not read locations, descriptions, or guests, and it does not cache, export, or back up any of it. Revoke calendar access in Android app settings and the widget keeps showing the date.

Private space support applies only while Duo is your Home app on Android 15 or later, which is the condition Android places on the hidden-profiles permission Duo declares. While your private space is locked, its apps are hidden from Home, All apps, search, suggestions, and badges, and Duo holds no list of them. Locking and unlocking go through Android's own quiet-mode control; Duo cannot see inside a locked profile. If Duo cannot read the profile's state for any reason, it treats the space as locked.

Removing an app opens Android's own uninstall confirmation, which is why Duo declares the package-deletion permission. Duo never removes an app itself, and the confirmation is Android's, not Duo's.

When another app asks to pin its own shortcut or widget to Home, Android starts Duo's confirmation screen. That screen is reachable by the system for this purpose; it accepts only a pin request Android itself supplies, never reads data from the requesting app's intent, and never launches anything on that app's behalf.

Android controls widget-binding approval and Home-app selection. Providers can require separate setup or permissions.

## Google and other apps

Discover and Google search use the installed Google app. Apps, search results, articles, and widgets may use their providers' network services and accounts. Those apps' policies and settings apply; Duo does not proxy their traffic or collect their content.

## Export, reports, and removal

A layout export is created only when you choose Save in Backup and select a destination. It can reveal installed apps, folder names, profile metadata, and layout preferences. Photos are excluded. An export taken while your private space is unlocked can include apps placed from it; exporting while it is locked cannot, because Duo holds no list of them then. Review it before sharing.

There is no automatic diagnostic upload. Screenshots and logs you manually attach to issues may contain personal information, widget content, account names, or work data. Review them first.

Uninstalling or clearing storage removes Duo's local settings, photos, and widget bindings. Exported files remain where you saved them. Android and device vendors may provide their own diagnostics independently of Duo.
