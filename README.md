# termux-whatsapp-ai-autoreply

A Termux Python script that:

1. reads your latest WhatsApp notification,
2. finds the sender's number in your contacts,
3. asks a free Groq model for a short reply,
4. opens the chat with the reply typed in,
5. presses **Enter** through a tiny custom keyboard (**SendKB**) so it sends.

No ADB, no Shizuku, no root.

```
notification -> Groq -> wa.me link -> chat opens -> broadcast -> SendKB presses Enter
```

## Limits (read first)

- The screen must be **on and unlocked**. A keyboard can only type into a text box that is on screen, so this is not true standby.
- **SendKB must be the active keyboard** when the broadcast fires. It has no keys, so you cannot type normally while it is selected. Switch back with the keyboard icon in the navigation bar.
- WhatsApp's **Enter is send** setting must be on.
- It only sees notifications still in your shade. Do not open the chat first.
- Group chats are not supported (the notification title is the group name).
- The message text is sent to Groq. Skip sensitive chats.
- The script sends messages as you with nobody checking them. Use `ALLOWED_SENDERS` in `main.py` while testing.

## Setup

### 1. Termux from F-Droid

Install **Termux** and **Termux:API** from F-Droid (https://f-droid.org). Both must come from the same source. The Google Play build does not work with the API.

```
termux-setup-storage
pkg update
pkg install python termux-api
```

Settings -> search "Notification access" -> turn on **Termux:API**.

Test:

```
termux-notification-list      # should print text starting with [
termux-contact-list           # allow the contacts permission
```

### 2. Groq key

Get a free key at https://console.groq.com/keys. Store it in a file, not in the script or a screenshot:

```
nano ~/.groq_key
```

Paste only the key, save with CTRL+X, Y, Enter. Never commit this file.

If Groq says 404 for the model, list what your key can use and change `MODEL` in `main.py`:

```
curl -s https://api.groq.com/openai/v1/models -H "Authorization: Bearer $(cat ~/.groq_key)" -H "User-Agent: termux" | grep -o '"id":"[^"]*"'
```

### 3. Build the SendKB keyboard APK

The APK is built free by GitHub Actions.

1. Open the **Actions** tab of this repo -> **build-apk** -> **Run workflow**.
2. When it shows a green tick (3-5 minutes), open the run and download the **SendKB** artifact. It is a zip containing `app-debug.apk`.
3. Extract and install it. Allow "install unknown apps" if asked.
4. Settings -> search "On-screen keyboard" / "Manage keyboards" -> turn on **Send Keyboard**.

### 4. WhatsApp

Settings -> Chats -> turn on **Enter is send**.

### 5. Get the script into Termux

```
cd ~/storage/downloads
git clone https://github.com/heyitsaadin/termux-whatsapp-ai-autoreply
```

(For a private repo, `pkg install git` first and use a GitHub token, or just download `termux/main.py` and save it in `Download/codes/python`.)

Edit `termux/main.py`: set `COUNTRY_CODE`, `MY_NAME`, and `ALLOWED_SENDERS = ["Amma"]`.

## Test the keyboard alone

Open any WhatsApp chat, type a word, tap the keyboard icon in the nav bar and choose **Send Keyboard**, then in Termux run:

```
python main.py --send-only
```

The message should send.

## Run it

1. Have someone message you. **Do not open the chat.**
2. Switch the keyboard to **Send Keyboard** (keyboard icon in nav bar, once the chat is open the first time you may need to do this before running).
3. Run:

```
cd ~/storage/downloads/codes/python
python main.py
```

If the keyboard is not selected at that moment, the chat opens with the reply typed in but does not send. Select the keyboard, then run `python main.py --send-only`.

## Standby mode (background)

```
python main.py --standby     # start in the background
python main.py --stop        # stop it
tail -f ~/wa_autoreply.log   # live log
```

Checks your WhatsApp notifications every few seconds and answers each new one. After sending it returns to the home screen, because WhatsApp posts no notification for a chat that is open on screen.

## Replying while a chat is open (chat watcher)

SendKB also contains an accessibility service, **Send Keyboard chat watcher**. It reads the WhatsApp chat you have open, asks the standby script for a reply (over `127.0.0.1:8765`, never leaves your phone), types it into the message box and taps send. The keyboard does not need to be switched for this.

Setup:

1. Rebuild the APK (Actions -> build-apk) and install it. If Android reports a conflict with the installed SendKB, uninstall the old one first and re-enable the keyboard.
2. Android 13+: Settings -> Apps -> Send Keyboard -> three dots -> **Allow restricted settings**.
3. Settings -> Accessibility -> Installed apps -> **Send Keyboard chat watcher** -> turn on.
4. Run `python main.py --standby`.

How it behaves:

- Only active while standby is running, and only inside WhatsApp.
- Messages already on screen when you open a chat are never answered.
- Ignored while you are scrolled up reading old messages.
- Never overwrites text you have typed in the message box.
- Group chats and senders outside `ALLOWED_SENDERS` are skipped.
- The AI sees the last few messages of the chat as context.
- If nothing happens, look for lines starting with `[app]` in the log. If WhatsApp changed its layout they list the view ids that were seen.

## Commands the keyboard accepts

```
am broadcast -a com.aadin.sendkb.SEND -p com.aadin.sendkb
am broadcast -a com.aadin.sendkb.TYPE -p com.aadin.sendkb --es text "hello"
```

## Troubleshooting

| Problem | Fix |
|---|---|
| `Could not read notifications` | Notification access for Termux:API. Toggle it off and on. |
| `Termux:API is not yet available on Google Play` | Wrong Termux build. Reinstall from F-Droid. |
| `INSTALL_FAILED_SHARED_USER_INCOMPATIBLE` | Uninstall every Termux app, then install Termux first, then Termux:API, from one source. |
| AI error 404 | Model retired. List models (above) and change `MODEL`. |
| AI error 401 | Bad or missing key in `~/.groq_key`. |
| Chat opens but nothing sends | SendKB is not the active keyboard, or Enter is send is off. |
| Enter adds a new line | Turn on Enter is send in WhatsApp. |

## Security

Never paste your API key into a terminal command or screenshot. If a key is exposed, delete it at https://console.groq.com/keys and make a new one.

Unofficial automation of WhatsApp may conflict with its terms of service. This uses only normal intents and a keyboard, but use it on your own chats at your own risk.
