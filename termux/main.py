#!/usr/bin/env python3
"""
WhatsApp AI auto-reply for Termux.

1. Reads the latest WhatsApp notification (termux-notification-list)
2. Looks up the sender's phone number in your contacts
3. Asks Groq for a short reply
4. Opens the chat with the reply typed in (wa.me link)
5. Tells the SendKB keyboard to press Enter, which sends it

Usage:
    python main.py              full run
    python main.py --send-only  just press send (to test the keyboard)
    python main.py --force 9876543210
                                skip notifications, AI-reply to "hey" and send
                                to that number right now (for testing)
      optional: --msg "text"    the incoming message the AI replies to
                --text "text"   send this exact text instead of an AI reply
"""
import json
import os
import re
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
ALLOWED_SENDERS = []                     # e.g. ["Amma"]; empty list = anyone
# -------------------------------------------


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


def latest_whatsapp_msg():
    r = subprocess.run(["termux-notification-list"],
                       capture_output=True, text=True)
    try:
        items = json.loads(r.stdout)
    except json.JSONDecodeError:
        print("Could not read notifications. Is Notification access on "
              "for Termux:API?")
        return None, None
    msgs = [n for n in items
            if n.get("packageName") == "com.whatsapp"
            and n.get("title") not in ("WhatsApp", "", None)]
    if not msgs:
        return None, None
    msgs.sort(key=lambda n: str(n.get("when", "")))
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


def main():
    if "--send-only" in sys.argv:
        press_send()
        return

    number = get_opt("--force")
    if number:
        force_send(number, get_opt("--msg") or "hey", get_opt("--text"))
        return

    key = load_key()
    if not key:
        sys.exit("No Groq key. Put it in ~/.groq_key (see README).")

    sender, message = latest_whatsapp_msg()
    print("From:", sender, "| Message:", message)
    if not sender or not message:
        sys.exit("No WhatsApp message found.")
    if ALLOWED_SENDERS and sender not in ALLOWED_SENDERS:
        sys.exit(f"{sender} is not in ALLOWED_SENDERS, skipping.")

    send_notification("Automated Reply", f"Generating reply to {sender}...")
    reply = ai_reply(key, sender, message)
    print("AI reply:", reply)

    phone = find_number(sender)
    if not reply or not phone:
        sys.exit("Missing reply or phone number.")

    open_chat(phone, reply)
    time.sleep(CHAT_LOAD_DELAY)
    press_send()


if __name__ == "__main__":
    main()
