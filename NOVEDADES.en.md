# What's new

<!-- English version of NOVEDADES.md. Same sections, with "## Next version" instead of
     "## Próxima versión". The app shows these notes when it's in English. -->

## Next version

- **Diagnostics** (Settings → Diagnostics): shows whether the filter really works (queries handled,
  lists loaded, which DNS is in use and which network it goes out through) and has a live test that
  checks that a normal site resolves and an ad domain is blocked.
- **Harder to bypass**: apps that ask public DNS servers directly (Google, Cloudflare, Quad9…) also go
  through the filter, and their encrypted DNS is cut so they use the phone's DNS.
- **More reliable lists**: a failed update never deletes the previous list, and when the author shrinks
  a list on purpose (like HaGeZi's threat list) it is no longer rejected forever.
- **Wi-Fi and mobile data at the same time**: the outgoing DNS is always the one of the network Android
  really uses.
- **Uses less without a connection**: encrypted DNS stops retrying endlessly when there is no network
  and comes back as soon as there is one.
- If Android's private DNS points to a service your lists block, the app now warns that there is no
  internet (it used to say the filter was bypassed).
- Fixes from an external security review: connections that cannot be protected are not used,
  downloads only go over HTTPS, the updater keeps the previous version until the new one is verified,
  and IPv6 is handled better.

## 0.4.1

- The APK no longer carries the encrypted list of libraries that Android Studio adds for Google
  (nobody else can read it). F-Droid and IzzyOnDroid ask for this, and Garbillo will be available there soon.
- No other changes since 0.4.0.

## 0.4.0

- **In English and Spanish**: the app uses your phone's language and you can change it in
  Settings → Language.
- **Optional encrypted DNS**: in Settings → Encrypted DNS you can send queries encrypted to Quad9,
  Cloudflare or Mullvad, so your carrier or the Wi-Fi can't see which websites you visit. On a Wi-Fi
  with a sign-in page (hotels, airports) the network's DNS is used while you sign in.
- **"Block connections without VPN" notice**: if that Android option is on, the phone can be left
  without internet; now the app warns you, Home shows "No internet" and explains how to turn it off.
- If the phone has another profile (for example, a work profile), you're told the filter doesn't cover it.
- **More stable**: an app making too many queries no longer slows down the others, the connection
  recovers faster when switching between Wi-Fi and mobile data, and loading lists uses less memory.
- The Nightly channel is now called **Beta**; if you had it selected, it changes on its own.
- The notes for each version are shown in your language, and if a version fixes an important bug the
  app tells you more insistently.
- Home shows "All apps" when none are excluded.

## 0.3.3

- **New welcome screens**: they help you set up what keeps the filter always on (notifications,
  battery and autostart) and let you choose how to download and whether to send error reports.
- **Download only over Wi-Fi** (on by default): lists and version checks download on their own as
  soon as you connect to Wi-Fi. If you tap "Update now" on mobile data, it asks you first.
- **Optional anonymous error reports**: if you turn them on, when something fails a report is sent
  with no websites, history, apps or identifiers. You can see the exact report before it's sent in
  Settings → Error reports.
- If the lists can't be downloaded when you turn on the filter, it tries again as soon as there's a
  connection.
- When updating from the app, it explains what to do if Google Play Protect blocks the installation.
- Development versions (Nightly) are always numbered ahead of the latest stable one, so an update
  never looks like a downgrade.

## 0.3.2

- **Xiaomi, Huawei, OPPO and Vivo**: a notice explains how to grant the "Autostart" permission,
  without which the system won't let the filter turn itself back on after restarting or updating.
- If the filter was on and you find it off, it turns on again when you open the app.
- Fixed: if a list can't be downloaded (for example, with no connection), the setting goes back to
  how it was and you're told, instead of staying checked without working.
- Fixed: changing the blocking level without a connection could leave the phone with no list; now the
  previous list is kept until the new one downloads correctly.
- The notes for each update are shown in full, without being cut mid-sentence.

## 0.3.1

- **Fixed: on Android 9 and 10, updates from the app failed** with the message "The download isn't
  Garbillo". If you have 0.3.0 on one of those Android versions, install this version once by hand
  from the website; from then on it updates itself.
- Fixed: in Settings, a section opened from the search could appear cut off.
- **More private history**: when you clear it, nothing is left in the phone's file, and old entries
  are deleted on time even if the filter has been off for days.

## 0.3.0

- **New name: Garbillo**, with its own icon. It updates over the version you have.
- **Threat protection**, on by default: blocks phishing, malware and scam websites, alerts you when it
  does and has its own section in Settings.
- **Report a website**: from the history you can send a domain to be reviewed (ads getting through,
  something that stops working or a dangerous website).
- **Real protection status**: the main screen warns you if no lists are loaded, if your network's DNS
  isn't responding or if Android's private DNS bypasses the filter.
- **Turns on by itself when the phone starts** if you had it on, without having to open the app.
- **No ads at startup**: the filter loads the lists before it starts filtering.
- If your network's DNS stops responding, another one is used automatically.
- More protection against fake DNS answers on public Wi-Fi networks.
- Large DNS answers arrive complete.
- App updates are only installed if they come from GitHub and are signed with the same key as the
  installed app.
- "View on the web" never opens sites marked as dangerous.
- Settings: a guide to keep the VPN always on, and links to the website and the privacy policy.
- Fixed: after adding an exception, a domain could stay blocked for a few minutes.

## 0.2.0

- **Blocks more by default.** The Balanced level uses the Hagezi Pro list and Strict uses Pro++.
- **DNS anti-evasion on by default**, so browsers and apps can't bypass the filter.
- **Your manufacturer's list**: on Samsung, Xiaomi, Oppo/Realme/OnePlus, Huawei/Honor, Vivo and Amazon
  phones, ads and tracking from the system itself are blocked too.
- **History: "View on the web"**: loads the website where a domain appeared and outlines in red what
  it puts there.
- **Settings organized into categories, with search.**
- **Visual blocking removed**: accessibility and screen capture permissions are no longer requested.
- Clear notices about what a blocker of this kind can't remove.

## 0.1.0

- First version: local DNS filter without root, Hagezi lists and extra layers, choice of apps,
  automatic list updates, history and app updates from GitHub.
