#!/usr/bin/env python3
"""
WhatsApp AI auto-reply for Termux.

1. Reads the latest WhatsApp notification (termux-notification-list)
2. Looks up the sender's phone number in your contacts
3. Asks Groq for a short reply
4. Opens the chat with the reply typed in (wa.me link)
5. Tells the SendKB keyboard to press Enter, which sends it

Usage:
    python main.py              full run (reply to the latest message once)
    python main.py --standby    keep running in the background and auto-reply
                                to every new WhatsApp message that arrives
    python main.py --stop       stop standby mode
    python main.py --send-only  just press send (to test the keyboard)
    python main.py --force 9876543210
                                skip notifications, AI-reply to "hey" and send
                                to that number right now (for testing)
      optional: --msg "text"    the incoming message the AI replies to
                --text "text"   send this exact text instead of an AI reply

Standby extras:
    python main.py --standby --fg   run in the foreground (see live output)
    tail -f ~/wa_autoreply.log      watch the background log
"""
import json
import os
import re
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request
from urllib.parse import quote

# ---------------- settings ----------------
COUNTRY_CODE = "91"                      # added when a number has no country code
MODEL = "openai/gpt-oss-120b"            # faster: "openai/gpt-oss-20b"
API_URL = "https://api.groq.com/openai/v1/chat/completions"
MY_NAME = "Aadin"
CHAT_LOAD_DELAY = 3.0                    # seconds to let WhatsApp open the chat
GO_HOME_AFTER_SEND = True                # leave WhatsApp after sending, so it
                                         # keeps posting notifications
AFTER_SEND_DELAY = 1.5                   # seconds to let the message go out
ALLOWED_SENDERS = []                     # e.g. ["Amma"]; empty list = anyone

POLL_INTERVAL = 4                        # standby: seconds between checks
REPLY_COOLDOWN = 20                      # standby: min seconds between replies
                                         # to the same sender (stops bot loops)
PID_FILE = os.path.expanduser("~/.wa_autoreply.pid")
LOG_FILE = os.path.expanduser("~/wa_autoreply.log")
STANDBY_NOTIF_ID = "wa-standby"
STANDBY_FLAGS = ("--standby", "--stanby")   # second one forgives the typo
# -------------------------------------------

# notifications that are not real messages (calls, summaries, ...)
NOT_A_MESSAGE = re.compile(
    r"\W*(\d+ new messages?|(missed|incoming|ongoing) (voice|video) call"
    r"|checking for new messages)\W*", re.I)


def load_key():
    """Read the Groq key from the env var, or from ~/.groq_key."""
    key = os.environ.get("GROQ_API_KEY")
    if key:
        return key.strip()
    path = os.path.expanduser("~/.groq_key")
    if os.path.exists(path):
        return open(path).read().strip()
    return None


def run(cmd):
    r = subprocess.run(cmd, capture_output=True, text=True)
    print(" ".join(cmd[:3]), "->", (r.stdout + r.stderr).strip()[:200])
    return r


def send_notification(title, message):
    run(["termux-notification", "--title", title,
         "--content", message, "--priority", "high"])


def whatsapp_msgs():
    """All current WhatsApp message notifications, oldest first.
    Returns None if the notification list couldn't be read."""
    r = subprocess.run(["termux-notification-list"],
                       capture_output=True, text=True)
    try:
        items = json.loads(r.stdout)
    except json.JSONDecodeError:
        return None
    msgs = [n for n in items
            if n.get("packageName") == "com.whatsapp"
            and n.get("title") not in ("WhatsApp", "", None)
            and not NOT_A_MESSAGE.fullmatch(n.get("content") or "")]
    msgs.sort(key=lambda n: str(n.get("when", "")))
    return msgs


def latest_whatsapp_msg():
    msgs = whatsapp_msgs()
    if msgs is None:
        print("Could not read notifications. Is Notification access on "
              "for Termux:API?")
        return None, None
    if not msgs:
        return None, None
    last = msgs[-1]
    return last["title"], last.get("content", "")


def to_digits(number):
    d = re.sub(r"\D", "", number)
    if len(d) == 10:
        d = COUNTRY_CODE + d
    return d


def find_number(name):
    # unsaved contact: the notification title is already a number
    if re.fullmatch(r"[+\d\s\-()]{7,}", name):
        return to_digits(name)
    r = subprocess.run(["termux-contact-list"],
                       capture_output=True, text=True)
    try:
        contacts = json.loads(r.stdout or "[]")
    except json.JSONDecodeError:
        return None
    for c in contacts:
        if isinstance(c, dict) and \
           c.get("name", "").strip().lower() == name.strip().lower():
            return to_digits(c["number"])
    return None


def ai_reply(key, sender, message):
    body = json.dumps({
        "model": MODEL,
        "messages": [
            {"role": "system", "content":
                f"You are {MY_NAME} replying to a WhatsApp message from "
                f"{sender}. Reply in 1-2 short, casual sentences, in the "
                "same language as the message. Output only the reply text."},
            {"role": "user", "content": message},
        ],
        "reasoning_effort": "low",
        "max_tokens": 600,
    }).encode()
    req = urllib.request.Request(API_URL, data=body, headers={
        "Content-Type": "application/json",
        "Authorization": f"Bearer {key}",
        "User-Agent": "termux-script",
    })
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            data = json.load(resp)
        text = (data["choices"][0]["message"].get("content") or "").strip()
        if not text:
            print("Empty reply. Raw:", json.dumps(data)[:300])
            return None
        return text
    except urllib.error.HTTPError as e:
        print("AI error:", e, e.read().decode()[:300])
    except Exception as e:
        print("AI error:", e)
    return None


def open_chat(phone, text=""):
    url = f"https://wa.me/{phone}"
    if text:
        url += f"?text={quote(text, safe='')}"
    run(["am", "start", "-a", "android.intent.action.VIEW",
         "-d", url, "-p", "com.whatsapp"])


def press_send():
    """Ask the SendKB keyboard to press Enter in the focused text box."""
    r = subprocess.run(["am", "broadcast", "-a", "com.aadin.sendkb.SEND",
                        "-p", "com.aadin.sendkb"],
                       capture_output=True, text=True)
    out = (r.stdout + r.stderr).strip()
    # the keyboard answers with data="OK: ..." or data="FAIL: ..."
    print("send ->", out.splitlines()[-1] if out else "no output")


def leave_chat():
    """Go back to the home screen after sending.

    WhatsApp posts no notification for a chat that is open on screen, so
    leaving it open makes standby miss every message that follows.
    """
    if not GO_HOME_AFTER_SEND:
        return
    time.sleep(AFTER_SEND_DELAY)
    run(["am", "start", "-a", "android.intent.action.MAIN",
         "-c", "android.intent.category.HOME"])


def handle_message(key, sender, message):
    """Reply to one message. Returns True if a reply was sent."""
    if ALLOWED_SENDERS and sender not in ALLOWED_SENDERS:
        print(f"{sender} is not in ALLOWED_SENDERS, skipping.")
        return False
    phone = find_number(sender)
    if not phone:
        print(f"No phone number for {sender} (group chat?), skipping.")
        return False

    send_notification("Automated Reply", f"Generating reply to {sender}...")
    reply = ai_reply(key, sender, message)
    print("AI reply:", reply)
    if not reply:
        return False

    open_chat(phone, reply)
    time.sleep(CHAT_LOAD_DELAY)
    press_send()
    leave_chat()
    send_notification("Auto-replied", f"To {sender}: {reply}")
    return True


# ---------------- standby mode ----------------

def running_pid():
    """PID of a running standby process, or None."""
    try:
        with open(PID_FILE) as f:
            pid = int(f.read().strip())
        os.kill(pid, 0)
    except (OSError, ValueError):
        return None
    return pid


def clear_pid_file():
    try:
        with open(PID_FILE) as f:
            if int(f.read().strip()) == os.getpid():
                os.remove(PID_FILE)
    except (OSError, ValueError):
        pass


def msg_sig(n):
    """Identity of a notification, so each one is only answered once."""
    return (n.get("title"), n.get("content"), str(n.get("when", "")))


def standby_loop():
    """Watch for new WhatsApp notifications and auto-reply to each one."""
    key = load_key()
    if not key:
        sys.exit("No Groq key. Put it in ~/.groq_key (see README).")
    other = running_pid()
    if other and other != os.getpid():
        sys.exit(f"Standby already running (PID {other}). "
                 "Stop it with: python main.py --stop")

    with open(PID_FILE, "w") as f:
        f.write(str(os.getpid()))
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))

    run(["termux-wake-lock"])        # keep the CPU awake while on standby
    run(["termux-notification", "--id", STANDBY_NOTIF_ID,
         "--title", "WhatsApp AI standby",
         "--content", "Listening for new messages",
         "--ongoing", "--priority", "low"])
    print(f"Standby on (PID {os.getpid()}), checking every {POLL_INTERVAL}s",
          flush=True)

    # whatever is already in the tray now is old news: don't answer it
    seen = {msg_sig(n) for n in (whatsapp_msgs() or [])}
    last_reply = {}                  # sender -> time of our last reply
    read_ok = True

    try:
        while True:
            time.sleep(POLL_INTERVAL)
            try:
                items = whatsapp_msgs()
                if items is None:
                    if read_ok:
                        print("Could not read notifications. Is Notification "
                              "access on for Termux:API?", flush=True)
                    read_ok = False
                    continue
                read_ok = True

                # newest unseen message per sender
                fresh = {}
                for n in items:
                    if msg_sig(n) not in seen and n.get("content"):
                        fresh[n["title"]] = n

                deferred = set()
                for sender, n in fresh.items():
                    if time.time() - last_reply.get(sender, 0) < REPLY_COOLDOWN:
                        deferred.add(msg_sig(n))   # try again after cooldown
                        continue
                    print(f"New message from {sender}: {n['content']}",
                          flush=True)
                    if handle_message(key, sender, n["content"]):
                        last_reply[sender] = time.time()

                seen = {msg_sig(n) for n in items} - deferred
            except Exception as e:
                print("Standby error:", e, flush=True)
    except KeyboardInterrupt:
        pass
    finally:
        run(["termux-wake-unlock"])
        run(["termux-notification-remove", STANDBY_NOTIF_ID])
        clear_pid_file()
        print("Standby stopped.", flush=True)


def start_background():
    """Launch standby mode detached from this terminal."""
    pid = running_pid()
    if pid:
        sys.exit(f"Standby already running (PID {pid}). "
                 "Stop it with: python main.py --stop")
    if not load_key():
        sys.exit("No Groq key. Put it in ~/.groq_key (see README).")
    log = open(LOG_FILE, "a")
    p = subprocess.Popen(
        [sys.executable, "-u", os.path.abspath(__file__), "--standby", "--fg"],
        stdout=log, stderr=log, stdin=subprocess.DEVNULL,
        start_new_session=True)
    print(f"Standby started in the background (PID {p.pid}).")
    print(f"Logs: {LOG_FILE}")
    print("Stop it with: python main.py --stop")


def stop_background():
    pid = running_pid()
    if not pid:
        sys.exit("Standby isn't running.")
    os.kill(pid, signal.SIGTERM)
    print(f"Stopped standby (PID {pid}).")


# ---------------- testing helpers ----------------

def get_opt(flag):
    """Return the value after `flag` in argv, or None if flag isn't used."""
    if flag not in sys.argv:
        return None
    i = sys.argv.index(flag)
    if i + 1 >= len(sys.argv):
        sys.exit(f"{flag} needs a value.")
    return sys.argv[i + 1]


def force_send(number, incoming, literal_text):
    """Testing mode: skip notifications and send to `number` right now."""
    phone = to_digits(number)
    if len(phone) < 8:
        sys.exit(f"'{number}' doesn't look like a valid number.")

    if literal_text:
        reply = literal_text
    else:
        key = load_key()
        if not key:
            sys.exit("No Groq key. Put it in ~/.groq_key (see README).")
        reply = ai_reply(key, number, incoming)
    print("Reply:", reply)
    if not reply:
        sys.exit("No reply generated.")

    open_chat(phone, reply)
    time.sleep(CHAT_LOAD_DELAY)
    press_send()
    leave_chat()


def main():
    if "--stop" in sys.argv:
        stop_background()
        return

    if "--send-only" in sys.argv:
        press_send()
        return

    number = get_opt("--force")
    if number:
        force_send(number, get_opt("--msg") or "hey", get_opt("--text"))
        return

    if any(f in sys.argv for f in STANDBY_FLAGS):
        if "--fg" in sys.argv:
            standby_loop()
        else:
            start_background()
        return

    key = load_key()
    if not key:
        sys.exit("No Groq key. Put it in ~/.groq_key (see README).")

    sender, message = latest_whatsapp_msg()
    print("From:", sender, "| Message:", message)
    if not sender or not message:
        sys.exit("No WhatsApp message found.")

    handle_message(key, sender, message)


if __name__ == "__main__":
    main()
