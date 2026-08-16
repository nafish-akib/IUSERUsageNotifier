# IUSER Usage Monitor + TP-Link PPPoE Rotator (Windows)

Automates the weekly PPPoE credential rotation that you currently do by hand:
fetch the IUT IUSER portal usage, and when the active credential crosses the
threshold, log into the room TP-Link router and switch the PPPoE credentials to
the next one in the saved list.

## Setup

1. Install Python 3.10+ from python.org (check "Add to PATH").
2. `pip install -r requirements.txt`
3. `Copy-Item config.example.json config.json` and fill in:
   - `router.ip` — the router's admin address (TP-Link default: `192.168.0.1`)
   - `router.admin_user` / `router.admin_pass` — router admin login
   - `credentials` — the list of IUSER usernames/passwords to rotate through
   - `threshold_percent` — rotate when usage reaches this % of the free limit
   - `cooldown_hours` — minimum hours between rotations (prevents double-swaps)
4. Test:
   - `python usage_monitor.py --check` — fetch and print usage only
   - `python usage_monitor.py --rotate` — force-rotate now
5. Install the scheduled task (PowerShell as Administrator):
   - `.\install_task.ps1` — runs silently every day at 10:00
   - `.\install_task.ps1 -Uninstall` — removes it

Everything is logged to `monitor.log`. State (active credential index, last
rotation time) lives in `state.json`.

## Supported router

The built-in driver targets the **classic TP-Link web UI** (login.html +
wancfg.cmd) used by the TL-WR840N / TL-WR841N / TL-WR940N / TL-WR842ND family
that is common in student rooms. The script logs in with the admin account,
opens the WAN config page, rewrites the PPPoE username/password, and submits
the form — exactly what you do manually, but automated.

If your router uses the newer tplogin.cn / LuCI interface, add a driver class
next to `TPLinkClassic` (implement `login()` and `set_pppoe()`) and select it
in `rotator_for()`.

> Note: the PPPoE change briefly drops the internet (the WAN reconnects with
> the new credentials). The router itself does not reboot.

## Security

`config.json` stores plaintext router admin and campus credentials. Keep the
file readable only by your Windows user account (right-click > Properties >
Security), and do **not** commit it to GitHub — only `config.example.json`
is committed.