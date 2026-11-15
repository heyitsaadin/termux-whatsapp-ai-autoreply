# Status Auto-Reply

An Android app that answers WhatsApp messages for you while you're busy.

Set what you're doing (**At school**, **Travelling to Goa**, **In a meeting**), turn it on, and anyone who messages you gets a short note like:

> Sam is at school, pls message later.

No account, no server, no root, no Discord, no Termux. Everything is set up inside the app and stays on your phone.

## How it works

- WhatsApp shows a notification with a **Reply** button when a message arrives. The app taps that button for you, so WhatsApp never has to open.
- Each person gets the note **once** (again if your status changes, or after 30 minutes).
- Group chats are skipped. Only one-to-one chats get a reply.
- No status set means no replies. **Stop** pauses everything.

## Install

1. Download the APK:
   - **Releases** page: `status-auto-reply.apk`, or
   - **Actions** tab, newest green **build-apk** run, then the `status-auto-reply-apk` file at the bottom (it is a zip, extract it).
2. Open it and allow "install unknown apps" if Android asks.
3. Open the app and tap **Grant** on each row:
   - **Notification access**: required, this is how it sees messages.
   - **Run in background**: required, so replies still go out with the screen off.
   - **Show notification**: optional.
4. Tap **Set status**, pick one, add a place and your name if you like, **Save**.
5. Make sure the top card says **Auto-reply is on**.

> **Android 13+:** if Notification access is greyed out, open Settings, Apps, Status Auto-Reply, tap the three dots, then **Allow restricted settings**. Then try again.

Updates install over the old version and keep your settings, because every build uses the same signing key. (If you have a build from before that change, uninstall it once first.)

## Set status

- **Quick status**: tap one of the chips.
- **Or type your own**: your own words win over the chip.
- **Place**: type to search, tap a recent place, or tap **Use my location** to fill in your town. "Travelling" + "Goa" becomes "Travelling to Goa".
- **Your name**: used in the reply. Leave it empty and the reply says "I'm at school, pls message later."
- **Clear status** turns replies off until you set a new one.

## Make your own copy

You don't need a computer, only a GitHub account.

1. Tap **Fork** at the top of this page.
2. In your fork, open the **Actions** tab and tap the green button to enable workflows.
3. Actions, **build-apk**, **Run workflow**. After about two minutes the APK is at the bottom of the run.
4. Want a download page? In your fork, **Releases**, **Draft a new release**, type a tag like `v1.0`, **Publish**. The `release-apk` workflow attaches the APK for you.

Easy things to change (edit the file on GitHub, commit, wait for the build):

| What | Where |
|---|---|
| App icon | replace `android-keyboard/app/src/main/res/drawable-nodpi/ic_launcher_image.png` (square PNG, 432 px, keep the logo in the middle 60%) |
| Wording of the reply | `Status.java`, the `Info` class |
| Quick status chips | `android-keyboard/app/src/main/res/layout/dialog_tag.xml` |
| App id (to install next to the original) | `applicationId` in `android-keyboard/app/build.gradle` |
| Show a toast for every skipped message | `DEBUG = true` in `ReplyListener.java` |

## Optional: smarter follow-ups with AI

The note above is all most people need. If someone sends a real follow-up question, a small helper in Termux can answer it using a free Groq model, and it knows your status. Without the helper, follow-ups simply get no reply.

1. Install **Termux** from F-Droid (https://f-droid.org). Not from Google Play.
2. In Termux: `pkg install python git`
3. Get a free key at https://console.groq.com/keys and save it: `nano ~/.groq_key`, paste only the key, save with CTRL+X, Y, Enter.
4. `git clone` your fork (or this repo), then `cd` into its `termux` folder.
5. `python main.py --standby` starts it. `python main.py --stop` stops it. The app's **AI helper** row shows whether it is running.

The helper only listens on `127.0.0.1`, so nothing else on your network can reach it. The text of follow-up messages is sent to Groq, so skip this for sensitive chats.

## Privacy

- Your status, name and recent places are saved only on your phone.
- No accounts, analytics or ads.
- The only internet use is place search. Text you type in the Place box (or your rough coordinates, if you tap **Use my location**) goes to the free Photon service (photon.komoot.io), built on OpenStreetMap, to find place names. It is optional.
- Location permission is only asked when you tap **Use my location**, and only coarse (town level).

## Limits

- Works with **WhatsApp** (`com.whatsapp`), not WhatsApp Business.
- It can only answer messages that raise a notification with a Reply button. Muted chats and chats open on screen don't.
- WhatsApp can change how its notifications work and break this.
- It sends real messages as you, with nobody checking. Try it with a friend first.
- Automating WhatsApp may go against its terms of service. Use it on your own account at your own risk.
- Battery savers from some phone makers (Xiaomi, Oppo, Vivo, Samsung and others) kill background apps. If replies stop with the screen off, turn off battery optimisation for this app and lock it in the recent apps list.

## Troubleshooting

| Problem | Fix |
|---|---|
| Nobody gets a reply | Top card must say **Auto-reply is on**, a status must be set, and **Notification access** must be on. |
| Notification access is greyed out | Android 13+: App info, three dots, **Allow restricted settings**. |
| Works with the screen on, not off | Set **Run in background** to unrestricted, and check your phone maker's battery settings. |
| Replies once, then never again to the same person | That is on purpose: once per person per 30 minutes, unless your status changes. |
| "Use my location" finds nothing | Turn on location in quick settings and try again. Or type the place. |
| Installing says the app conflicts | Uninstall the old copy once, then install again. |

## What's in this repo

```
android-keyboard/   the Android app (Java, Material 3)
termux/main.py      optional AI helper for follow-ups
.github/workflows/  build-apk (every push) and release-apk (when you publish a release)
```

The folder is called `android-keyboard` for historical reasons: the first version was a custom keyboard. A tiny leftover keyboard, **Send Keyboard**, is still inside the app. You can ignore it and never need to enable it.
