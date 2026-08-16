#!/usr/bin/env python3
"""
IUSER Usage Monitor + TP-Link PPPoE Rotator (Windows / Python 3.10+)

Fetches the IUT IUSER portal usage for the active campus credential and, when
the usage crosses the configured threshold, logs into the room TP-Link router
and switches the PPPoE credentials to the next one in the saved list.

Commands:
  python usage_monitor.py --check      Fetch and print usage only (no rotation).
  python usage_monitor.py --rotate     Force-rotate to the next PPPoE credential now.
  python usage_monitor.py              Default: check usage, rotate if over threshold.
  python usage_monitor.py --dry-run    Same as default but never touch the router.

State:
  state.json (next to this file) remembers which credential is active and the
  last rotation time (cooldown prevents rotating twice in quick succession).
"""

import argparse
import base64
import json
import logging
import os
import re
import sys
from datetime import datetime, timedelta

import requests
from bs4 import BeautifulSoup

HERE = os.path.dirname(os.path.abspath(__file__))
CONFIG_PATH = os.path.join(HERE, "config.json")
STATE_PATH = os.path.join(HERE, "state.json")
LOG_PATH = os.path.join(HERE, "monitor.log")

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(message)s",
    handlers=[
        logging.FileHandler(LOG_PATH, encoding="utf-8"),
        logging.StreamHandler(sys.stdout),
    ],
)
log = logging.getLogger("iuser-monitor")

PORTAL_BASE = "http://10.220.20.12"


# ---------------------------------------------------------------------------
# Config / state
# ---------------------------------------------------------------------------

def load_config():
    if not os.path.exists(CONFIG_PATH):
        log.error("config.json not found next to the script. Copy config.example.json "
                  "to config.json and fill in your credentials.")
        sys.exit(2)
    with open(CONFIG_PATH, encoding="utf-8") as f:
        return json.load(f)


def load_state():
    if os.path.exists(STATE_PATH):
        with open(STATE_PATH, encoding="utf-8") as f:
            return json.load(f)
    return {"active_index": 0, "last_rotation": None}


def save_state(state):
    with open(STATE_PATH, "w", encoding="utf-8") as f:
        json.dump(state, f, indent=2)


# ---------------------------------------------------------------------------
# IUSER portal usage fetch (mirrors the Android app's logic)
# ---------------------------------------------------------------------------

def fetch_usage(username, password):
    """Returns (used_seconds, free_seconds, error). error is "" on success."""
    session = requests.Session()
    session.headers.update({"User-Agent": "Mozilla/5.0"})
    try:
        login_page = session.get(f"{PORTAL_BASE}/login", timeout=15)
        login_page.raise_for_status()

        match = re.search(r"""name=["']_token["']\s+value=["']([^"']+)["']""", login_page.text)
        if not match:
            return 0, 0, "CSRF token not found"
        token = match.group(1)

        resp = session.post(
            f"{PORTAL_BASE}/login",
            data={"username": username, "password": password, "_token": token},
            headers={"Referer": f"{PORTAL_BASE}/login"},
            timeout=15,
        )
        resp.raise_for_status()

        dash = session.get(f"{PORTAL_BASE}/dashboard", timeout=15)
        dash.raise_for_status()
        html = dash.text

        if not re.search(r"User Profile|invoicefor|Logout", html, re.IGNORECASE):
            return 0, 0, "Login failed"

        soup = BeautifulSoup(html, "html.parser")
        data = {}
        for row in soup.select("table.invoicefor tbody tr"):
            cells = row.select("td")
            if len(cells) >= 2:
                data[cells[0].get_text(strip=True).rstrip(":")] = cells[1].get_text(strip=True)

        free = parse_duration(data.get("Free Limit", "0 secs"))
        used = parse_duration(data.get("Total Use", "0 secs"))
        return used, free, ""
    except requests.RequestException as e:
        return 0, 0, f"Network error: {e}"
    except Exception as e:  # noqa: BLE001
        return 0, 0, f"Unexpected error: {e}"


def parse_duration(text):
    total = 0
    for match in re.finditer(r"(\d+)\s*(hrs?|hours?|mins?|minutes?|secs?|seconds?)", text, re.I):
        n = int(match.group(1))
        unit = match.group(2).lower()
        if unit.startswith("h"):
            total += n * 3600
        elif unit.startswith("m"):
            total += n * 60
        else:
            total += n
    return total


def format_duration(total_seconds):
    seconds = max(0, int(total_seconds))
    hrs, seconds = divmod(seconds, 3600)
    mins, seconds = divmod(seconds, 60)
    parts = []
    if hrs:
        parts.append(f"{hrs} hr{'s' if hrs != 1 else ''}")
    if mins:
        parts.append(f"{mins} min{'s' if mins != 1 else ''}")
    if seconds or not parts:
        parts.append(f"{seconds} sec{'s' if seconds != 1 else ''}")
    return " ".join(parts)


# ---------------------------------------------------------------------------
# TP-Link classic web UI driver (login.html + wancfg.cmd)
#
# Covers the classic TP-Link generation (TL-WR840N, TL-WR841N, TL-WR940N,
# TL-WR842ND and similar) that is common in IUT student rooms.
# If your router uses the newer tplogin.cn / LuCI interface, implement another
# driver class below and pick it in `rotator_for()`.
# ---------------------------------------------------------------------------

class TPLinkClassic:
    def __init__(self, base_url, admin_user, admin_password):
        self.base = base_url.rstrip("/")
        self.admin_user = admin_user
        self.admin_password = admin_password
        self.session = requests.Session()

    def login(self):
        # Old-gen TP-Link stores the password base64-encoded in the form.
        payload = base64.b64encode(self.admin_password.encode()).decode()
        for path in ("/login.html", "/login.htm"):
            try:
                r = self.session.post(
                    f"{self.base}{path}",
                    data={"userName": self.admin_user, "pcPassword": payload},
                    headers={"Referer": f"{self.base}/"},
                    timeout=10,
                )
                if r.ok and "Authorization" in self.session.cookies:
                    log.info("Router login OK (%s)", path)
                    return True
            except requests.RequestException:
                continue
        return False

    def _wancfg_fields(self):
        r = self.session.get(f"{self.base}/wancfg.cmd", timeout=10)
        r.raise_for_status()
        soup = BeautifulSoup(r.text, "html.parser")
        fields = {}
        form_action = "/wancfg.cmd"
        form = soup.find("form", id="form0") or soup.find("form")
        if form and form.get("action"):
            form_action = form["action"]
        for inp in soup.find_all("input"):
            name = inp.get("name")
            if name:
                fields[name] = inp.get("value", "")
        for sel in soup.find_all("select"):
            name = sel.get("name")
            if not name:
                continue
            selected = sel.find("option", selected=True)
            fields[name] = selected.get("value") if selected else (sel.get("value") or "")
        return form_action, fields

    def set_pppoe(self, new_username, new_password):
        form_action, fields = self._wancfg_fields()
        if "wan_ppp_username" not in fields:
            return "PPPoE form not found (router may use a different UI generation)"
        fields["wan_ppp_username"] = new_username
        fields["wan_ppp_password"] = new_password
        fields["wan_ppp_confirm"] = new_password
        url = self.base + form_action if form_action.startswith("/") else self.base + "/" + form_action
        r = self.session.post(
            url,
            data=fields,
            headers={"Referer": f"{self.base}/wancfg.cmd"},
            timeout=15,
        )
        if not r.ok:
            return f"Router returned HTTP {r.status_code}"
        # TP-Link classic answers with "success" / "ok" in the body on success.
        body = r.text.lower()
        if "success" in body or "ok" in body or "result" in body:
            return ""
        return "Router did not confirm the change"


def rotator_for(config):
    base = f"http://{config['router']['ip']}"
    return TPLinkClassic(base, config["router"]["admin_user"], config["router"]["admin_pass"])


# ---------------------------------------------------------------------------
# Rotation logic
# ---------------------------------------------------------------------------

def next_credential(creds, active_index):
    return (active_index + 1) % len(creds)


def rotate(config, state, force=False):
    creds = config["credentials"]
    router_cfg = config["router"]

    if len(creds) < 2:
        log.warning("Need at least 2 PPPoE credentials to rotate.")
        return False

    cooldown_hours = config.get("cooldown_hours", 12)
    if not force and state.get("last_rotation"):
        last = datetime.fromisoformat(state["last_rotation"])
        if datetime.now() - last < timedelta(hours=cooldown_hours):
            log.info("Cooldown active (last rotation %s), skipping.", last.isoformat())
            return False

    idx = next_credential(creds, state["active_index"])
    target = creds[idx]

    log.info("Rotating to credential %d: %s", idx + 1, target["username"])
    router = rotator_for(config)
    if not router.login():
        log.error("Router login failed (check ip/admin credentials in config.json).")
        return False

    error = router.set_pppoe(target["username"], target["password"])
    if error:
        log.error("PPPoE update failed: %s", error)
        return False

    state["active_index"] = idx
    state["last_rotation"] = datetime.now().isoformat()
    save_state(state)
    log.info("PPPoE credentials switched to %s", target["username"])
    return True


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description="IUSER usage monitor + TP-Link PPPoE rotator")
    parser.add_argument("--check", action="store_true", help="fetch usage only")
    parser.add_argument("--rotate", action="store_true", help="force rotation now")
    parser.add_argument("--dry-run", action="store_true", help="never touch the router")
    args = parser.parse_args()

    config = load_config()
    state = load_state()

    creds = config["credentials"]
    if not creds:
        log.error("No credentials in config.json")
        sys.exit(2)
    active = creds[state["active_index"]]

    log.info("Active credential: %s", active["username"])
    used, free, error = fetch_usage(active["username"], active["password"])
    if error:
        log.warning("Usage fetch failed: %s", error)
    else:
        percent = (used / free * 100) if free > 0 else 0.0
        log.info("Used: %s / %s (%.1f%%)", format_duration(used), format_duration(free), percent)

    if args.check:
        return

    if args.rotate:
        rotate(config, state, force=True)
        return

    # Default: rotate when over threshold (or dry-run report).
    threshold = config.get("threshold_percent", 90)
    if error:
        log.info("No rotation: usage could not be fetched.")
        return
    if free > 0 and used / free * 100 >= threshold:
        if args.dry_run:
            target = creds[next_credential(creds, state["active_index"])]
            log.info("[dry-run] Would rotate to %s", target["username"])
        else:
            rotate(config, state)
    else:
        log.info("Usage below threshold (%.0f%%), no rotation needed.", threshold)


if __name__ == "__main__":
    main()