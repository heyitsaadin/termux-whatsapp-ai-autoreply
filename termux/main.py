#!/usr/bin/env python3
"""
AI helper for the Status Auto-Reply app.

The Android app answers the FIRST message from each person with a fixed line
built from the status you set in the app. When that person sends a real follow-up, the app
asks this script what to say. The script runs a tiny server on 127.0.0.1 that
only the app calls, so your Groq key never leaves Termux.

Usage:
    python main.py --standby       start the helper in the background
    python main.py --stop          stop it
    python main.py --standby --fg  run in the foreground (see live output)
    python main.py --ask "Can I talk to him?" --status "travelling"
                                   try the AI from the terminal
    tail -f ~/wa_autoreply.log     watch the background log

The Groq key is read from GROQ_API_KEY (exported in ~/.bashrc) or from the
file ~/.groq_key.
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
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# ---------------- settings ----------------
MODEL = "openai/gpt-oss-120b"            # faster: "openai/gpt-oss-20b"
API_URL = "https://api.groq.com/openai/v1/chat/completions"
PORT = 8765                              # must match Ai.java in the app
PID_FILE = os.path.expanduser("~/.wa_autoreply.pid")
LOG_FILE = os.path.expanduser("~/wa_autoreply.log")
STANDBY_FLAGS = ("--standby", "--stanby")   # second one forgives the typo
NO_REPLY = "NO_REPLY"
# -------------------------------------------


def load_key():
    """Groq key: env var, then ~/.groq_key, then an export line in ~/.bashrc."""
    key = os.environ.get("GROQ_API_KEY")
    if key:
        return key.strip()
    path = os.path.expanduser("~/.groq_key")
    if os.path.exists(path):
        return open(path).read().strip()
    for name in (".bashrc", ".bash_profile", ".profile"):
        try:
            with open(os.path.expanduser("~/" + name)) as f:
                text = f.read()
        except OSError:
            continue
        m = re.search(r"""GROQ_API_KEY\s*=\s*["']?([^"'\s]+)""", text)
        if m:
            return m.group(1)
    return None


def run(cmd):
    r = subprocess.run(cmd, capture_output=True, text=True)
    print(" ".join(cmd[:3]), "->", (r.stdout + r.stderr).strip()[:200],
          flush=True)
    return r


def ai_reply(key, chat, message, status, history, name="", notice=""):
    """Custom reply for a follow-up message, or None if none is needed.

    status:  what you are doing, e.g. "at school"
    history: earlier turns, oldest first, WITHOUT `message`:
             [{"from": "them" | "me", "text": ...}]
    """
    turns = []
    for h in history or []:
        if not isinstance(h, dict):
            continue
        text = str(h.get("text") or "").strip()
        if text:
            role = "assistant" if h.get("from") == "me" else "user"
            turns.append({"role": role, "content": text})

    who = (name or "").strip() or "the owner"
    notice = (notice or "").strip() or f"{who} is {status}, pls message later."
    system = (
        f"You are the auto-reply assistant for {who}'s WhatsApp. "
        f"{who} can't chat right now.\n"
        f"Their current status: \"{who} is {status}.\"\n"
        f"This person already got the notice: \"{notice}\" and has now sent "
        "a follow-up.\n"
        "Decide:\n"
        "- If the message is a greeting, filler, a repeat, spam, or needs no "
        f"answer, output exactly {NO_REPLY}.\n"
        "- Otherwise write ONE short reply (max 2 sentences) that takes their "
        "status into account. Speak as their assistant in the third person "
        f"(\"{who} is...\", \"they...\"), in the same language as the "
        "message.\n"
        "- Never invent facts (where they are, when they'll be free), never "
        "promise or agree to anything for them, never share private details. "
        "Do not repeat the notice word for word.\n"
        f"Output only the reply text, or {NO_REPLY}."
    )

    body = json.dumps({
        "model": MODEL,
        "messages": [{"role": "system", "content": system}]
                    + turns
                    + [{"role": "user", "content": message}],
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
    except urllib.error.HTTPError as e:
        print("AI error:", e, e.read().decode()[:300], flush=True)
        return None
    except Exception as e:
        print("AI error:", e, flush=True)
        return None

    if not text or text.upper().startswith(NO_REPLY):
        return None
    return text[:400]


# ---------------- the local server ----------------

def make_handler(key):
    class Handler(BaseHTTPRequestHandler):
        def _send(self, obj, code=200):
            data = json.dumps(obj).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):               # open http://127.0.0.1:8765 to check it's up
            self._send({"ok": True, "helper": "status-auto-reply"})

        def do_POST(self):
            if self.path != "/reply":
                return self._send({"error": "not found"}, 404)
            try:
                size = int(self.headers.get("Content-Length", 0))
                data = json.loads(self.rfile.read(size) or b"{}")
            except ValueError:
                return self._send({"error": "bad json"}, 400)
            try:
                chat = str(data.get("chat") or "").strip()
                message = str(data.get("message") or "").strip()
                status = str(data.get("status") or "busy").strip()
                if not message:
                    return self._send({"reply": None})
                print(f"[ask] {chat} ({status}): {message}", flush=True)
                reply = ai_reply(key, chat, message, status,
                                 data.get("history") or [],
                                 str(data.get("name") or ""),
                                 str(data.get("notice") or ""))
                print("[ai]", reply if reply else NO_REPLY, flush=True)
                return self._send({"reply": reply})
            except Exception as e:
                print("Server error:", e, flush=True)
                return self._send({"reply": None}, 500)

        def log_message(self, *args):
            pass                        # keep the log quiet

    return Handler


def running_pid():
    """PID of a running helper, or None."""
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


def serve():
    """Run the helper server (foreground)."""
    key = load_key()
    if not key:
        sys.exit("No Groq key. Export GROQ_API_KEY in ~/.bashrc or put it in "
                 "~/.groq_key.")
    other = running_pid()
    if other and other != os.getpid():
        sys.exit(f"Already running (PID {other}). Stop it with: "
                 "python main.py --stop")

    try:
        server = ThreadingHTTPServer(("127.0.0.1", PORT), make_handler(key))
    except OSError as e:
        sys.exit(f"Could not listen on 127.0.0.1:{PORT}: {e}")

    with open(PID_FILE, "w") as f:
        f.write(str(os.getpid()))
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
    run(["termux-wake-lock"])            # keep Termux awake with the screen off
    print(f"AI helper on 127.0.0.1:{PORT} (PID {os.getpid()})", flush=True)

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        run(["termux-wake-unlock"])
        clear_pid_file()
        print("AI helper stopped.", flush=True)


def start_background():
    """Launch the helper detached from this terminal."""
    pid = running_pid()
    if pid:
        sys.exit(f"Already running (PID {pid}). Stop it with: "
                 "python main.py --stop")
    if not load_key():
        sys.exit("No Groq key. Export GROQ_API_KEY in ~/.bashrc or put it in "
                 "~/.groq_key.")
    log = open(LOG_FILE, "a")
    p = subprocess.Popen(
        [sys.executable, "-u", os.path.abspath(__file__), "--standby", "--fg"],
        stdout=log, stderr=log, stdin=subprocess.DEVNULL,
        start_new_session=True)
    print(f"AI helper started in the background (PID {p.pid}).")
    print(f"Logs: {LOG_FILE}")
    print("Stop it with: python main.py --stop")


def stop_background():
    pid = running_pid()
    if not pid:
        sys.exit("The AI helper isn't running.")
    os.kill(pid, signal.SIGTERM)
    print(f"Stopped the AI helper (PID {pid}).")


def get_opt(flag):
    """Return the value after `flag` in argv, or None if flag isn't used."""
    if flag not in sys.argv:
        return None
    i = sys.argv.index(flag)
    if i + 1 >= len(sys.argv):
        sys.exit(f"{flag} needs a value.")
    return sys.argv[i + 1]


def main():
    if "--stop" in sys.argv:
        stop_background()
        return

    question = get_opt("--ask")
    if question:
        key = load_key()
        if not key:
            sys.exit("No Groq key. Export GROQ_API_KEY in ~/.bashrc or put "
                     "it in ~/.groq_key.")
        reply = ai_reply(key, "test", question,
                         get_opt("--status") or "busy", [])
        print(reply if reply else f"({NO_REPLY}: the AI would stay quiet)")
        return

    if any(f in sys.argv for f in STANDBY_FLAGS):
        if "--fg" in sys.argv:
            serve()
        else:
            start_background()
        return

    print(__doc__)


if __name__ == "__main__":
    main()
