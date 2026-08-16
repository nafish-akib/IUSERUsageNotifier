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
# TP-Link CGI driver (TL-WR845N and similar classic models)
#
# Reverse-engineered against a live TL-WR845N admin panel:
#   1. Auth is a plain cookie `Authorization=Basic base64(user:pass)` (no POST).
#   2. Reads/writes are POSTs to /cgi?<action>&_=<timestamp> with a text/plain
#      body of `[OID#stack#0,0,0,0,0,0]index,count\r\n` + `key=value\r\n` lines
#      (trailing CRLF required). Actions: 1=GET, 2=SET, 5=GL (list).
#   3. GL WAN_COMMON_INTF_CFG -> Ethernet stack, GL WAN_PPP_CONN -> enabled
#      PPPoE stack, GET its config, SET new credentials.
#   4. `[error]71014` in the response means success.
# ---------------------------------------------------------------------------

ACT_GET = 1
ACT_SET = 2
ACT_GL = 5
STACK_NULL = "0,0,0,0,0,0"


class TPLinkCgi:
    def __init__(self, base_url, admin_user, admin_password):
        self.base = base_url.rstrip("/")
        self.auth = "Basic " + base64.b64encode(
            f"{admin_user}:{admin_password}".encode()
        ).decode()
        self.session = requests.Session()

    def _cgi(self, action, oid, stack, attrs):
        """attrs: list of 'name' or 'key=value' strings. Returns list of dicts."""
        body = f"[{oid}#{stack}#{STACK_NULL}]0,{len(attrs)}\r\n"
        if attrs:
            body += "\r\n".join(attrs) + "\r\n"
        ts = int(datetime.now().timestamp() * 1000)
        r = self.session.post(
            f"{self.base}/cgi?{action}&_={ts}",
            data=body.encode(),
            headers={
                "Cookie": f"Authorization={self.auth}",
                "Content-Type": "text/plain; charset=utf-8",
                "X-Requested-With": "XMLHttpRequest",
                "Referer": f"{self.base}/mainFrame.htm",
            },
            timeout=10,
        )
        r.raise_for_status()
        return self._parse(r.text)

    @staticmethod
    def _parse(text):
        instances = []
        current = None
        for raw in text.split("\n"):
            line = raw.rstrip("\r")
            if not line:
                continue
            if line.startswith("["):
                end = line.find("]")
                stack = line[1:end] if end > 0 else line[1:]
                if stack == "error":  # [error]71014 = end/success marker
                    break
                current = {"__stack": stack}
                instances.append(current)
            else:
                if "=" in line and current is not None:
                    key, value = line.split("=", 1)
                    current[key] = value
        return instances

    def login(self):
        try:
            intf = self._cgi(ACT_GL, "WAN_COMMON_INTF_CFG", STACK_NULL, ["WANAccessType"])
            ok = any(i.get("WANAccessType") == "Ethernet" for i in intf)
            if not ok:
                log.error("Router login failed: no Ethernet WAN interface found "
                          "(check ip/admin credentials in config.json)")
                return False
            log.info("Router login OK")
            return True
        except requests.RequestException as e:
            log.error("Router login failed: %s", e)
            return False

    def set_pppoe(self, new_username, new_password):
        try:
            intf = self._cgi(ACT_GL, "WAN_COMMON_INTF_CFG", STACK_NULL, ["WANAccessType"])
            eth_stack = next(
                (i["__stack"] for i in intf if i.get("WANAccessType") == "Ethernet"),
                None,
            )
            if not eth_stack:
                return "Ethernet WAN interface not found"

            ppp = self._cgi(ACT_GL, "WAN_PPP_CONN", STACK_NULL, ["enable"])
            ppp_stack = next(
                (i["__stack"] for i in ppp if i.get("enable") == "1"),
                ppp[0]["__stack"] if ppp else None,
            )
            if not ppp_stack:
                return "PPPoE connection not found"

            current = self._cgi(ACT_GET, "WAN_PPP_CONN", ppp_stack, [])
            current = current[0] if current else {}
            second_conn = current.get("secondConnection", "sec_conn_disable")
            trigger = current.get("connectionTrigger", "AlwaysOn")

            result = self._cgi(
                ACT_SET,
                "WAN_PPP_CONN",
                ppp_stack,
                [
                    "enable=1",
                    f"username={new_username}",
                    f"password={new_password}",
                    f"secondConnection={second_conn}",
                    f"connectionTrigger={trigger}",
                ],
            )
            if result:
                return "Router did not confirm the change"
            return ""
        except requests.RequestException as e:
            return f"Router request failed: {e}"


def rotator_for(config):
    base = f"http://{config['router']['ip']}"
    return TPLinkCgi(base, config["router"]["admin_user"], config["router"]["admin_pass"])


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
        log.info("Used: %s / %s (%.1f%%, %.1f hours)",
                 format_duration(used), format_duration(free), percent, used / 3600.0)

    if args.check:
        return

    if args.rotate:
        rotate(config, state, force=True)
        return

    # Default: rotate when over threshold (or dry-run report).
    threshold_hours = config.get("threshold_hours", 190.0)
    if error:
        log.info("No rotation: usage could not be fetched.")
        return
    used_hours = used / 3600.0
    if used_hours >= threshold_hours:
        if args.dry_run:
            target = creds[next_credential(creds, state["active_index"])]
            log.info("[dry-run] Would rotate to %s", target["username"])
        else:
            rotate(config, state)
    else:
        log.info("Usage %.1fh below threshold %.1fh, no rotation needed.",
                 used_hours, threshold_hours)


if __name__ == "__main__":
    main()